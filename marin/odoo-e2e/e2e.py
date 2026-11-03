"""End to end against a real Odoo: mail over SMTP into the fork's James, configured from marin/conf, with Odoo's
mail_inbox and James's mailboxes on one private PostgreSQL, James reading Odoo's addresses as a read-only role.

usage: e2e.py <run dir> <james repo> <odoo checkout> <odoo python> [test ...]"""

import base64
import imaplib
import json
import os
import re
import secrets
import shutil
import signal
import smtplib
import socketserver
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from email.message import EmailMessage
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lane"))
sys.path.insert(0, os.environ["DKIMPY"])
import dkim  # noqa: E402
from james import edit, free_port, wait_until_up, webadmin  # noqa: E402

PG_BINDIR = Path(os.environ.get("PG_BINDIR", "/usr/lib/postgresql/18/bin"))
DOMAIN = "agromarin.test"
MARIA = f"maria.orozco@{DOMAIN}"
MARIA_PASSWORD = None
READER = "james_odoo_reader"
ODOO_ACCOUNT = "odoo@agromarin.mx"
ODOO_ACCOUNT_PASSWORD = secrets.token_urlsafe(18)
SELECTOR = "james1"
REFRESH = 30
RUN_ID = str(int(time.time()))

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}", flush=True)


def wait(predicate, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(1)
    return False


class Gateway(socketserver.ThreadingTCPServer):
    """The SMTP relay James hands outbound mail to, standing in for SES: it accepts everything and keeps it."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self):
        self.received = []
        super().__init__(("127.0.0.1", 0), GatewaySession)
        self.port = self.server_address[1]
        threading.Thread(target=self.serve_forever, daemon=True).start()


class GatewaySession(socketserver.StreamRequestHandler):
    def reply(self, line):
        self.wfile.write(line.encode() + b"\r\n")

    def handle(self):
        self.reply("220 gateway.test ESMTP")
        sender, recipients = None, []
        while line := self.rfile.readline():
            command = line.decode(errors="replace").strip()
            verb = command[:4].upper()
            if verb in ("EHLO", "HELO"):
                self.reply("250-gateway.test")
                self.reply("250 8BITMIME")
            elif verb == "MAIL":
                sender, recipients = command.split(":", 1)[1].strip().split()[0].strip("<>"), []
                self.reply("250 OK")
            elif verb == "RCPT":
                recipients.append(command.split(":", 1)[1].strip().split()[0].strip("<>"))
                self.reply("250 OK")
            elif verb == "DATA":
                self.reply("354 go ahead")
                data = bytearray()
                while (chunk := self.rfile.readline()) not in (b".\r\n", b""):
                    data += chunk[1:] if chunk.startswith(b"..") else chunk
                self.server.received.append({"sender": sender, "recipients": recipients, "data": bytes(data)})
                self.reply("250 queued")
            elif verb == "QUIT":
                self.reply("221 bye")
                return
            else:
                self.reply("250 OK")


class Cluster:
    def __init__(self, run):
        self.dir = run / "pg"
        self.port = free_port()
        subprocess.run([PG_BINDIR / "initdb", "-D", self.dir, "-U", "postgres", "--auth=trust", "-E", "UTF8",
                        "--locale=C"], check=True, capture_output=True)
        (self.dir / "pg_hba.conf").write_text("local all postgres trust\nhost all all 127.0.0.1/32 scram-sha-256\n")
        subprocess.run([PG_BINDIR / "pg_ctl", "-D", self.dir, "-l", run / "pg.log", "-w", "start", "-o",
                        f"-p {self.port} -k {run} -c listen_addresses=127.0.0.1 -c fsync=off"],
                       check=True, capture_output=True)
        self.socket_dir = run
        self.sql("postgres", "CREATE ROLE james SUPERUSER LOGIN PASSWORD 'secret1'",
                 "CREATE DATABASE james_e2e OWNER james",
                 "CREATE ROLE odoo CREATEDB LOGIN PASSWORD 'odoo'",
                 "CREATE DATABASE odoo_e2e OWNER odoo TEMPLATE template0 LC_COLLATE 'C' LC_CTYPE 'C'",
                 f"CREATE ROLE {READER} LOGIN PASSWORD 'reader'")

    def sql(self, database, *statements):
        out = subprocess.run(
            ["psql", "-h", str(self.socket_dir), "-p", str(self.port), "-U", "postgres", "-d", database, "-v",
             "ON_ERROR_STOP=1", "-Atq", *[arg for statement in statements for arg in ("-c", statement)]],
            check=True, capture_output=True, text=True)
        return out.stdout.strip()

    def stop(self):
        subprocess.run([PG_BINDIR / "pg_ctl", "-D", self.dir, "-m", "immediate", "stop"], capture_output=True)


class Odoo:
    def __init__(self, run, checkout, python, cluster):
        self.run = run
        self.checkout = checkout
        self.python = python
        self.cluster = cluster
        self.port = free_port()
        self.conf = run / "odoo.conf"
        self.conf.write_text(
            "[options]\n"
            f"addons_path = {checkout}/addons\n"
            "server_wide_modules = base,rpc,web\n"
            f"data_dir = {run}/odoo-data\n"
            f"db_host = 127.0.0.1\ndb_port = {cluster.port}\ndb_user = odoo\ndb_password = odoo\n"
            "db_maxconn = 16\n"
            "list_db = False\n"
            f"http_interface = 127.0.0.1\nhttp_port = {self.port}\n"
            "workers = 0\nlog_level = info\n")
        self.env = {**os.environ, "ODOO_API_ENCRYPTION_KEY": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode()}
        self.process = None

    def bin(self, *args, command=()):
        return [str(self.python), str(self.checkout / "odoo-bin"), *command, "-c", str(self.conf), "-d", "odoo_e2e",
                *args]

    def install(self):
        with (self.run / "odoo-install.log").open("w") as log:
            subprocess.run(self.bin("-i", "mail_inbox", "--stop-after-init", "--no-http"), check=True, stdout=log,
                           stderr=subprocess.STDOUT, env=self.env)

    def shell(self, action, arg=""):
        out = subprocess.run(self.bin("--no-http", command=("shell",)), input=(Path(__file__).parent / "odoo_actions.py").read_text(),
                             capture_output=True, text=True, env={**self.env, "E2E_ACTION": action, "E2E_ARG": arg})
        for line in out.stdout.splitlines():
            if line.startswith("RESULT "):
                return json.loads(line[len("RESULT "):])
        raise SystemExit(f"e2e.py: odoo shell {action} failed:\n{out.stdout[-2000:]}\n{out.stderr[-4000:]}")

    def start(self):
        log = (self.run / "odoo.log").open("w")
        self.process = subprocess.Popen(self.bin(), stdout=log, stderr=subprocess.STDOUT, env=self.env,
                                        start_new_session=True)

        def up():
            if self.process.poll() is not None:
                raise SystemExit(f"e2e.py: Odoo exited with {self.process.returncode}; see {self.run}/odoo.log")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/web/health", timeout=5) as response:
                    return response.status == 200
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                return False

        if not wait(up, 120):
            raise SystemExit(f"e2e.py: Odoo did not answer; see {self.run}/odoo.log")

    def sql(self, statement):
        return self.cluster.sql("odoo_e2e", statement)

    def stop(self):
        if self.process and self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            self.process.wait(30)


class James:
    def __init__(self, run, repo, cluster, odoo, server_id, token, gateway, dkim_key):
        self.home = run / "james"
        self.home.mkdir()
        app = repo / "server" / "apps" / "postgres-app"
        shutil.copy(app / "target" / "james-server-postgres-app.jar", self.home)
        shutil.copytree(app / "target" / "james-server-postgres-app.lib", self.home / "james-server-postgres-app.lib")
        conf = self.home / "conf"
        shutil.copytree(app / "sample-configuration", conf)
        for overlay in [*(app / "sample-configuration-single").iterdir(), *(repo / "marin" / "conf").iterdir()]:
            if overlay.name != "README.md":
                shutil.copy(overlay, conf / overlay.name)
        self.ports = {name: free_port() for name in ("smtp", "submission", "imap", "jmap", "webadmin")}
        self.password = secrets.token_urlsafe(24)
        token_file = conf / "odoo-inbound.token"
        token_file.write_text(token + "\n")
        token_file.chmod(0o600)
        self.token_file = token_file
        (conf / "dkim-james1.pem").write_bytes(dkim_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        edit(conf / "postgres.properties",
             (r"database\.name=james", "database.name=james_e2e"),
             (r"database\.host=postgres", "database.host=127.0.0.1"),
             (r"database\.port=5432", f"database.port={cluster.port}"),
             (r"ssl\.mode=allow", "ssl.mode=disable"))
        edit(conf / "odoo.properties",
             (r"(?m)^database\.url=.*$", f"database.url=jdbc:postgresql://127.0.0.1:{cluster.port}/odoo_e2e"),
             (r"(?m)^database\.password=.*$", "database.password=reader"))
        smtp = conf / "smtpserver.xml"
        text = smtp.read_text()
        text = text.replace("<bind>0.0.0.0:25</bind>", f"<bind>127.0.0.1:{self.ports['smtp']}</bind>")
        text = text.replace("<bind>0.0.0.0:587</bind>", f"<bind>127.0.0.1:{self.ports['submission']}</bind>")
        text = re.sub(r'<smtpserver enabled="true">(\s*<jmxName>smtpserver-TLS</jmxName>)', r'<smtpserver enabled="false">\1',
                      text)
        text = text.replace("<authorizedAddresses>127.0.0.0/8</authorizedAddresses>",
                            "<authorizedAddresses>127.0.0.2/32</authorizedAddresses>")
        text = text.replace("<username>odoo@agromarin.mx</username>", f"<username>{ODOO_ACCOUNT}</username>")
        text = text.replace("<password>change-me</password>", f"<password>{ODOO_ACCOUNT_PASSWORD}</password>")
        smtp.write_text(text)
        edit(conf / "imapserver.xml",
             (r"<bind>0\.0\.0\.0:143</bind>", f"<bind>127.0.0.1:{self.ports['imap']}</bind>"),
             (r'<imapserver enabled="true">(\s*<jmxName>imapserver-ssl</jmxName>)', r'<imapserver enabled="false">\1'))
        with (conf / "jmap.properties").open("a") as jmap:
            jmap.write(f"\njmap.port={self.ports['jmap']}\nurl.prefix=http://127.0.0.1:{self.ports['jmap']}\n")
        edit(conf / "jmx.properties", (r"(?m)^jmx\.enabled=true$", "jmx.enabled=false"))
        edit(conf / "webadmin.properties",
             (r"(?m)^port=8000$", f"port={self.ports['webadmin']}"),
             (r"(?m)^password\.generate=true$", f"password={self.password}"))
        edit(conf / "mailetcontainer.xml",
             (r"<url>https://odoo\.example\.com/mail/inbound/1</url>",
              f"<url>http://127.0.0.1:{odoo.port}/mail/inbound/{server_id}</url>"),
             (r"<tokenFile>conf/odoo-inbound\.token</tokenFile>", f"<tokenFile>{token_file}</tokenFile>"),
             (r"<maxAttempts>288</maxAttempts>", "<maxAttempts>10</maxAttempts>"),
             (r"<delay>5m</delay>", "<delay>5s</delay>"),
             (r"<gateway>email-smtp\.us-east-2\.amazonaws\.com</gateway>", "<gateway>127.0.0.1</gateway>"),
             (r"<gatewayPort>587</gatewayPort>", f"<gatewayPort>{gateway.port}</gatewayPort>"),
             (r"\s*<gatewayUsername>change-me</gatewayUsername>", ""),
             (r"\s*<gatewayPassword>change-me</gatewayPassword>", ""),
             (r"<startTLS>true</startTLS>", "<startTLS>false</startTLS>"),
             (r"<verifyServerIdentity>true</verifyServerIdentity>", "<verifyServerIdentity>false</verifyServerIdentity>"),
             (r"<delayTime>[^<]*</delayTime>", "<delayTime>5 * 5 seconds</delayTime>"),
             (r"<maxRetries>16</maxRetries>", "<maxRetries>5</maxRetries>"))
        logs = self.home / "logs"
        logs.mkdir()
        edit(conf / "logback.xml",
             (r"<file>/logs/james\.log</file>", f"<file>{logs}/james.log</file>"),
             (r"<fileNamePattern>/logs/", f"<fileNamePattern>{logs}/"))
        subprocess.run(
            ["keytool", "-genkeypair", "-alias", "james", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
             "-keystore", str(conf / "keystore"), "-storepass", "james72laBalle", "-keypass", "james72laBalle",
             "-dname", f"CN=mail.{DOMAIN}", "-validity", "2"],
            check=True, capture_output=True)
        self.out = self.home / "james.out"
        with self.out.open("w") as out:
            self.process = subprocess.Popen(
                ["java", "-Xms256m", "-Xmx1g", "-Dworking.directory=.", "-Dlogback.configurationFile=conf/logback.xml",
                 "-Djdk.tls.ephemeralDHKeySize=2048", "-Dextra.props=conf/jvm.properties",
                 "-jar", "james-server-postgres-app.jar"],
                cwd=self.home, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        state = {"home": str(self.home), "ports": self.ports, "password": self.password}
        wait_until_up(state, self.process)
        webadmin(state, "PUT", f"/domains/{DOMAIN}")
        self.state = state

    def log(self):
        return self.out.read_text(errors="replace")

    def health(self):
        request = urllib.request.Request(f"http://127.0.0.1:{self.ports['webadmin']}/healthcheck/checks/Odoo%20directory",
                                         headers={"Password": self.password})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)["status"]
        except urllib.error.HTTPError as error:
            return json.load(error)["status"]

    def stop(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(60)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)


def message(message_id, to, subject, sender="Zed Supplier <zed@supplier.test>", **headers):
    mail = EmailMessage()
    mail["From"] = sender
    mail["To"] = to
    mail["Subject"] = subject
    mail["Message-Id"] = f"<{message_id}>"
    for name, value in headers.items():
        mail[name.replace("_", "-")] = value
    mail.set_content(f"Hola, {subject}")
    return mail


class Suite:
    def __init__(self, cluster, odoo, james, ids, gateway, dkim_key):
        self.cluster = cluster
        self.odoo = odoo
        self.james = james
        self.ids = ids
        self.gateway = gateway
        der = dkim_key.public_key().public_bytes(serialization.Encoding.DER,
                                                 serialization.PublicFormat.SubjectPublicKeyInfo)
        self.dkim_record = b"v=DKIM1; k=rsa; p=" + base64.b64encode(der)

    def dkim_valid(self, data):
        def txt(name, timeout=5):
            return self.dkim_record if name == f"{SELECTOR}._domainkey.{DOMAIN}.".encode() else None
        return dkim.verify(data, dnsfunc=txt)

    def relayed(self, subject):
        return next((mail for mail in self.gateway.received if subject.encode() in mail["data"]), None)

    def send(self, mail, rcpts, sender="zed@supplier.test", login=None):
        port = self.james.ports["submission" if login else "smtp"]
        with smtplib.SMTP("127.0.0.1", port, timeout=30) as smtp:
            if login:
                smtp.starttls(context=ssl._create_unverified_context())
                smtp.login(*login)
            return smtp.send_message(mail, from_addr=sender, to_addrs=rcpts)

    def rcpt(self, address):
        with smtplib.SMTP("127.0.0.1", self.james.ports["smtp"], timeout=30) as smtp:
            smtp.ehlo()
            smtp.mail("zed@supplier.test")
            code, text = smtp.rcpt(address)
            smtp.rset()
            return code, text.decode(errors="replace")

    def odoo_messages(self, message_id, model="discuss.channel"):
        return self.odoo.sql(f"SELECT count(*) FROM mail_message WHERE message_id = '<{message_id}>' AND model = '{model}'")

    def imap(self, *criteria):
        with imaplib.IMAP4("127.0.0.1", self.james.ports["imap"]) as imap:
            imap.login(MARIA, MARIA_PASSWORD)
            imap.select("INBOX")
            _, data = imap.search(None, *criteria)
            messages = []
            for number in data[0].split():
                _, parts = imap.fetch(number, "(RFC822)")
                messages.append(parts[0][1].decode(errors="replace"))
            return messages

    def t1_odoo_address(self):
        mid = f"t1-{RUN_ID}@supplier.test"
        self.send(message(mid, f"ventas@{DOMAIN}", f"Cotizacion T1 {RUN_ID}"), [f"ventas@{DOMAIN}"])
        check("T1 ventas@ reaches Odoo", wait(lambda: self.odoo_messages(mid) == "1", 40))
        row = self.odoo.sql(
            "SELECT a.alias_name || ' | ' || c.channel_type || ' | ' || p.email FROM mail_message m "
            "JOIN discuss_channel c ON c.id = m.res_id JOIN mail_inbox i ON i.id = c.inbox_id "
            "JOIN mail_alias a ON a.id = i.alias_id JOIN res_partner p ON p.id = c.correspondent_partner_id "
            f"WHERE m.model = 'discuss.channel' AND m.message_id = '<{mid}>'")
        check("T1 it lands in the Ventas email conversation with Zed", row == "ventas | email | zed@supplier.test", row)
        self.send(message(mid, f"ventas@{DOMAIN}", f"Cotizacion T1 {RUN_ID}"), [f"ventas@{DOMAIN}"])
        time.sleep(8)
        check("T1 the same message delivered twice stays one Odoo message", self.odoo_messages(mid) == "1")

    def t2_mailbox(self):
        subject = f"Contrato T2 {RUN_ID}"
        mid = f"t2-{RUN_ID}@supplier.test"
        self.send(message(mid, MARIA, subject), [MARIA])
        check("T2 maria@ is in her IMAP inbox", wait(lambda: len(self.imap("SUBJECT", f'"{subject}"')) == 1, 20))
        check("T2 maria@ did not go to Odoo", self.odoo.sql(
            f"SELECT count(*) FROM mail_message WHERE message_id = '<{mid}>'") == "0")
        auth = "Basic " + base64.b64encode(f"{MARIA}:{MARIA_PASSWORD}".encode()).decode()
        base = f"http://127.0.0.1:{self.james.ports['jmap']}"
        session = json.load(urllib.request.urlopen(urllib.request.Request(
            f"{base}/jmap/session", headers={"Authorization": auth, "Accept": "application/json"}), timeout=15))
        account = next(iter(session["accounts"]))
        calls = {"using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"], "methodCalls": [
            ["Email/query", {"accountId": account, "filter": {"subject": subject}}, "q"],
            ["Email/get", {"accountId": account, "#ids": {"resultOf": "q", "name": "Email/query", "path": "/ids"},
                           "properties": ["subject"]}, "g"]]}
        emails = json.load(urllib.request.urlopen(urllib.request.Request(
            f"{base}/jmap", data=json.dumps(calls).encode(),
            headers={"Authorization": auth, "Content-Type": "application/json", "Accept": "application/json"}),
            timeout=15))["methodResponses"][1][1]["list"]
        check("T2 JMAP Email/query finds it", [e["subject"] for e in emails] == [subject], json.dumps(emails)[:120])

    def t3_unknown(self):
        code, text = self.rcpt(f"nobody@{DOMAIN}")
        check("T3 nobody@ is refused at RCPT with 550", code == 550, f"{code} {text}")

    def t4_token(self):
        mid = f"t4-{RUN_ID}@supplier.test"
        mark = len(self.james.log())
        good = self.james.token_file.read_text()
        self.james.token_file.write_text("wrong-token\n")
        try:
            self.send(message(mid, f"ventas@{DOMAIN}", f"Factura T4 {RUN_ID}"), [f"ventas@{DOMAIN}"])
            check("T4 a refused token is retried, not bounced",
                  wait(lambda: "odoo answered 401" in self.james.log()[mark:], 40))
            check("T4 nothing reached Odoo meanwhile", self.odoo_messages(mid) == "0")
        finally:
            self.james.token_file.write_text(good)
        check("T4 the retry delivers once the token is restored", wait(lambda: self.odoo_messages(mid) == "1", 60))

    def t5_new_alias(self):
        assert self.rcpt(f"pedidos@{DOMAIN}")[0] == 550
        committed = self.odoo.shell("create_inbox", "pedidos")["committed_at"]
        accepted = wait(lambda: self.rcpt(f"pedidos@{DOMAIN}")[0] == 250, 70)
        delay = time.time() - committed
        check("T5 an inbox created in Odoo is accepted at RCPT within a minute", accepted and delay <= 60,
              f"{delay:.0f} s")

    def t6_archived_alias(self):
        committed = self.odoo.shell("archive_inbox", "pedidos")["committed_at"]
        refused = wait(lambda: self.rcpt(f"pedidos@{DOMAIN}")[0] == 550, 70)
        delay = time.time() - committed
        check("T6 an inbox archived in Odoo is refused at RCPT within a minute", refused and delay <= 60,
              f"{delay:.0f} s")

    def t7_archived_meanwhile(self):
        self.odoo.shell("create_inbox", "compras")
        assert wait(lambda: self.rcpt(f"compras@{DOMAIN}")[0] == 250, 70)
        self.odoo.sql("UPDATE mail_alias SET alias_name = NULL, alias_full_name = NULL WHERE alias_name = 'compras'")
        subject = f"Pedido T7 {RUN_ID}"
        self.send(message(f"t7-{RUN_ID}@{DOMAIN}", f"compras@{DOMAIN}", subject, sender=f"Maria Orozco <{MARIA}>"),
                  [f"compras@{DOMAIN}"], sender=MARIA, login=(MARIA, MARIA_PASSWORD))
        bounced = wait(lambda: any(subject in report and "report-type=delivery-status" in report
                                   for report in self.imap("ALL")), 40)
        report = next((r for r in self.imap("ALL") if subject in r and "delivery-status" in r), "")
        check("T7 an address Odoo dropped after James read it bounces to the sender with a DSN",
              bounced and "Action: failed" in report and "Status: 5.1.1" in report
              and "Diagnostic-Code: smtp; 550 5.1.1 There is no mailbox for this address." in report,
              re.sub(r"\s+", " ", report[report.find("Reporting-MTA"):][:400]))
        check("T7 the DSN shows no Odoo internals", "alias" not in report.lower().replace(f"compras@{DOMAIN}", ""))

    def t8_reply(self):
        mid = f"t8-{RUN_ID}@supplier.test"
        note = self.ids["note_message_id"]
        self.send(message(mid, f"catchall@{DOMAIN}", "Re: Cotizacion enviada", In_Reply_To=note, References=note),
                  [f"catchall@{DOMAIN}"])
        landed = wait(lambda: self.odoo.sql(
            f"SELECT res_id FROM mail_message WHERE message_id = '<{mid}>' AND model = 'res.partner'")
            == str(self.ids["partner_id"]), 40)
        check("T8 a reply to an Odoo message through catchall@ lands on its record", landed)

    def t9_both(self):
        mark = len(self.james.log())
        self.odoo.sql(f"UPDATE mail_alias SET alias_name = 'maria.orozco', alias_full_name = '{MARIA}' WHERE alias_name = 'ventas'")
        try:
            deferred = wait(lambda: self.rcpt(MARIA)[0] == 451, 70)
            code, text = self.rcpt(MARIA)
            check("T9 an address both James and Odoo answer is deferred", deferred and text.startswith("4.3.5"),
                  f"{code} {text}")
            check("T9 and James logs it as an error",
                  f"{MARIA} is both an Odoo address and a James user" in self.james.log()[mark:])
        finally:
            self.odoo.sql(f"UPDATE mail_alias SET alias_name = 'ventas', alias_full_name = 'ventas@{DOMAIN}' WHERE alias_name = 'maria.orozco'")
        check("T9 the mailbox accepts again once Odoo drops it", wait(lambda: self.rcpt(MARIA)[0] == 250, 70))

    def t11_odoo_sends(self):
        self.odoo.shell("outgoing", json.dumps({"host": "127.0.0.1", "port": self.james.ports["submission"],
                                                 "user": ODOO_ACCOUNT, "password": ODOO_ACCOUNT_PASSWORD}))
        subject = f"Orden de compra T11 {RUN_ID}"
        sent = self.odoo.shell("notify", subject)
        check("T11 Odoo hands its notification to James submission", sent["queued"] >= 1 and not sent["unsent"], json.dumps(sent))
        check("T11 James relays it to the gateway", wait(lambda: self.relayed(subject) is not None, 40))
        mail = self.relayed(subject) or {"sender": None, "recipients": [], "data": b""}
        check("T11 to Zed, from Odoo's bounce address", mail["recipients"] == ["zed@supplier.test"]
              and mail["sender"] == f"bounce@{DOMAIN}", f"{mail['sender']} -> {mail['recipients']}")
        check("T11 it is DKIM-signed for agromarin, and dkimpy verifies the signature",
              b"DKIM-Signature:" in mail["data"] and f"d={DOMAIN}".encode() in mail["data"]
              and self.dkim_valid(mail["data"]))
        check("T11 its From is Odoo's notification address, in the signed domain",
              f"<notifications@{DOMAIN}>".encode() in mail["data"].split(b"\r\n\r\n", 1)[0])

    def t12_person_sends(self):
        subject = f"Respuesta T12 {RUN_ID}"
        self.send(message(f"t12-{RUN_ID}@{DOMAIN}", "zed@supplier.test", subject, sender=f"Maria Orozco <{MARIA}>"),
                  ["zed@supplier.test"], sender=MARIA, login=(MARIA, MARIA_PASSWORD))
        check("T12 Maria's own mail is relayed DKIM-signed too",
              wait(lambda: self.relayed(subject) is not None, 40) and self.dkim_valid(self.relayed(subject)["data"]))
        tampered = self.relayed(subject)["data"].replace(subject.encode(), b"Altered " + subject.encode())
        check("T12 an altered copy fails dkimpy's verification", not self.dkim_valid(tampered))

    def t13_odoo_account(self):
        with smtplib.SMTP("127.0.0.1", self.james.ports["submission"], timeout=30) as smtp:
            smtp.starttls(context=ssl._create_unverified_context())
            try:
                smtp.login(ODOO_ACCOUNT, "wrong")
                refused = False
            except smtplib.SMTPAuthenticationError:
                refused = True
        check("T13 the Odoo account with a wrong password is refused", refused)

    def imap_login(self, password):
        with imaplib.IMAP4("127.0.0.1", self.james.ports["imap"]) as imap:
            try:
                imap.login(MARIA, password)
                return True
            except imaplib.IMAP4.error:
                return False

    def t14_odoo_password(self):
        check("T14 Maria's mail key, made in Odoo, opens her mailbox", self.imap_login(MARIA_PASSWORD))
        check("T14 her Odoo login password does not", not self.imap_login(self.ids["odoo_password"]))
        check("T14 the James users are Odoo's mailboxes, read-only",
              self.webadmin_status("PUT", f"/users/nuevo@{DOMAIN}", {"password": "x"}) >= 400)

    def webadmin_status(self, method, path, body=None):
        try:
            return webadmin(self.james.state, method, path, body)
        except urllib.error.HTTPError as error:
            return error.code

    def t15_revoked_key(self):
        committed = self.odoo.shell("revoke_mail_keys")["committed_at"]
        refused = wait(lambda: not self.imap_login(MARIA_PASSWORD), 70)
        delay = time.time() - committed
        check("T15 a mail key revoked in Odoo stops opening the mailbox within a minute", refused and delay <= 60,
              f"{delay:.0f} s")

    def t16_closed_mailbox(self):
        assert self.rcpt(MARIA)[0] == 250
        committed = self.odoo.shell("close_mailbox")["committed_at"]
        refused = wait(lambda: self.rcpt(MARIA)[0] == 550, 70)
        delay = time.time() - committed
        check("T16 a mailbox closed in Odoo is refused at RCPT within a minute", refused and delay <= 60,
              f"{delay:.0f} s")

    def t10_health(self):
        check("T10 James's Odoo directory health check is healthy", self.james.health() == "healthy",
              self.james.health())


TESTS = ["t1_odoo_address", "t2_mailbox", "t3_unknown", "t4_token", "t5_new_alias", "t6_archived_alias",
         "t7_archived_meanwhile", "t8_reply", "t9_both", "t11_odoo_sends", "t12_person_sends", "t13_odoo_account",
         "t10_health", "t14_odoo_password", "t15_revoked_key", "t16_closed_mailbox"]


def main(run, repo, checkout, python, names):
    run.mkdir(parents=True)
    cluster = Cluster(run)
    odoo = james = gateway = None
    try:
        odoo = Odoo(run, checkout, python, cluster)
        print("e2e: installing mail_inbox", flush=True)
        odoo.install()
        ids = odoo.shell("setup")
        cluster.sql("odoo_e2e", f"GRANT USAGE ON SCHEMA public TO {READER}",
                    f"GRANT SELECT ON mail_gateway_recipient, mail_mailbox, mail_mailbox_key TO {READER}")
        global MARIA_PASSWORD
        MARIA_PASSWORD = ids["mail_key"]
        odoo.start()
        print("e2e: starting James", flush=True)
        gateway = Gateway()
        dkim_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        james = James(run, repo, cluster, odoo, ids["server_id"], ids["token"], gateway, dkim_key)
        suite = Suite(cluster, odoo, james, ids, gateway, dkim_key)
        for name in names or TESTS:
            getattr(suite, next(test for test in TESTS if test == name or test.startswith(name + "_")))()
        errors = [line for line in james.log().splitlines() if " ERROR " in line and "is both an Odoo address" not in line]
        check("James logged no other error", not errors, "; ".join(errors[:3]))
    finally:
        for number, mail in enumerate(gateway.received if gateway else []):
            (run / f"gateway-{number}.eml").write_bytes(mail["data"])
        if james:
            james.stop()
        if odoo:
            odoo.stop()
        cluster.stop()
    print(f"{sum(results)} passed, {len(results) - sum(results)} failed of {len(results)}")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5:]))
