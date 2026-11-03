"""The restore drill: James on PostgreSQL backed up as production backs up (pgBackRest with its WAL archived,
encrypted; restic for the off-site copy of the repository and James's configuration), the host lost, everything
restored from the restic copy, and every mailbox compared with what it held before the loss.

usage: drill.py <run dir> <james repo> <pgbackrest bin dir> <restic bin dir>"""

import hashlib
import imaplib
import json
import os
import random
import re
import shutil
import signal
import subprocess
import sys
import time
from email.message import EmailMessage
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lane"))
from james import configure, free_port, wait_until_up, webadmin  # noqa: E402

PG_BINDIR = Path(os.environ.get("PG_BINDIR", "/usr/lib/postgresql/18/bin"))
DOMAIN = "lane.test"
USERS = {f"ana@{DOMAIN}": "drill-pass-1", f"beto@{DOMAIN}": "drill-pass-2"}
STANZA = "mail"
SEED = 20261103

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}", flush=True)


def run(*command, **kwargs):
    return subprocess.run([str(part) for part in command], check=True, capture_output=True, text=True, **kwargs)


class Cluster:
    def __init__(self, run_dir, pgbackrest):
        self.run_dir = run_dir
        self.data = run_dir / "pg"
        self.port = free_port()
        self.pgbackrest = pgbackrest
        self.repo = run_dir / "pgbackrest-repo"
        self.conf = run_dir / "pgbackrest.conf"
        cipher = run_dir / "pgbackrest-cipher"
        if not cipher.exists():
            cipher.write_text(os.urandom(24).hex())
        self.conf.write_text(
            f"[global]\nrepo1-path={self.repo}\nrepo1-cipher-type=aes-256-cbc\nrepo1-cipher-pass={cipher.read_text()}\n"
            "repo1-retention-full=2\ncompress-type=zst\nstart-fast=y\n"
            f"lock-path={run_dir}/pgbackrest-lock\nlog-path={run_dir}/pgbackrest-log\nlog-level-console=warn\n"
            f"spool-path={run_dir}/pgbackrest-spool\n\n"
            f"[{STANZA}]\npg1-path={self.data}\npg1-port={self.port}\npg1-socket-path={run_dir}\npg1-user=postgres\n")

    def backrest(self, *args):
        return run(self.pgbackrest, f"--config={self.conf}", f"--stanza={STANZA}", *args)

    def init(self):
        run(PG_BINDIR / "initdb", "-D", self.data, "-U", "postgres", "--auth-local=trust", "--auth-host=scram-sha-256",
            "--pwfile=/dev/stdin", "-E", "UTF8", "--locale=C", input="postgres\n")
        with (self.data / "postgresql.conf").open("a") as conf:
            conf.write(f"\narchive_mode = on\narchive_command = '{self.pgbackrest} --config={self.conf} "
                       f"--stanza={STANZA} archive-push %p'\narchive_timeout = 60\nmax_connections = 300\n"
                       "wal_level = replica\n")

    def start(self):
        run(PG_BINDIR / "pg_ctl", "-D", self.data, "-l", self.run_dir / "pg.log", "-w", "start", "-o",
            f"-p {self.port} -k {self.run_dir} -c listen_addresses=127.0.0.1")

    def stop(self):
        subprocess.run([PG_BINDIR / "pg_ctl", "-D", self.data, "-m", "fast", "-w", "stop"], capture_output=True)

    def sql(self, statement, database="postgres"):
        return run("psql", "-h", self.run_dir, "-p", self.port, "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1",
                   "-Atqc", statement).stdout.strip()


class James:
    def __init__(self, run_dir, repo, cluster):
        self.home = run_dir / "james"
        self.home.mkdir()
        app = repo / "server" / "apps" / "postgres-app"
        shutil.copy(app / "target" / "james-server-postgres-app.jar", self.home)
        shutil.copytree(app / "target" / "james-server-postgres-app.lib", self.home / "james-server-postgres-app.lib")
        self.ports, self.password = configure(self.home, app, cluster.port, "james_drill")
        self.process = None

    @property
    def state(self):
        return {"home": str(self.home), "ports": self.ports, "password": self.password}

    def start(self):
        out = (self.home / "james.out").open("a")
        self.process = subprocess.Popen(
            ["java", "-Xms256m", "-Xmx1g", "-Dworking.directory=.", "-Dlogback.configurationFile=conf/logback.xml",
             "-Djdk.tls.ephemeralDHKeySize=2048", "-Dextra.props=conf/jvm.properties",
             "-jar", "james-server-postgres-app.jar"],
            cwd=self.home, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        wait_until_up(self.state, self.process)

    def stop(self):
        if self.process and self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(60)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)

    def imap(self, user):
        imap = imaplib.IMAP4("127.0.0.1", self.ports["imap"])
        imap.login(user, USERS[user])
        return imap


def message(rng, number, user):
    mail = EmailMessage()
    mail["From"] = f"Proveedor {number} <p{number}@supplier.test>"
    mail["To"] = user
    mail["Subject"] = f"Pedido {number} con acentos: año, señal, ¿listo?"
    mail["Message-Id"] = f"<drill-{number}@supplier.test>"
    mail.set_content("Línea\r\n" * rng.randint(1, 400) + f"fin {number}\n")
    if number % 7 == 0:
        mail.add_attachment(rng.randbytes(rng.randint(20_000, 400_000)), maintype="application", subtype="pdf",
                            filename=f"factura-{number}.pdf")
    return mail.as_bytes()


def seed(james, rng, first, count, expunge_every):
    for user in USERS:
        imap = james.imap(user)
        for mailbox in ("INBOX", "Archivo/2026"):
            imap.create(mailbox)
            for number in range(first, first + count):
                flags = "(\\Seen)" if number % 3 == 0 else "(\\Flagged $Etiqueta)" if number % 5 == 0 else "()"
                imap.append(mailbox, flags, imaplib.Time2Internaldate(1793000000 + number * 3600),
                            message(rng, number, user))
            imap.select(mailbox)
            _, data = imap.uid("SEARCH", None, "ALL")
            doomed = [uid for uid in data[0].split() if int(uid) % expunge_every == 0]
            if doomed:
                imap.uid("STORE", b",".join(doomed), "+FLAGS", "(\\Deleted)")
                imap.expunge()
        imap.logout()


def snapshot(james):
    state = {}
    for user in USERS:
        imap = james.imap(user)
        _, listing = imap.list()
        names = sorted(re.search(rb'"([^"]*)"$|(\S+)$', line).group(0).strip(b'"').decode() for line in listing)
        for name in names:
            _, status = imap.status(f'"{name}"', "(MESSAGES UIDNEXT UIDVALIDITY HIGHESTMODSEQ)")
            imap.select(f'"{name}"', readonly=True)
            messages = []
            _, data = imap.uid("FETCH", "1:*", "(FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])")
            for part in data:
                if isinstance(part, tuple):
                    head = part[0].decode()
                    messages.append({"uid": re.search(r"UID (\d+)", head).group(1),
                                     "flags": sorted(re.search(r"FLAGS \(([^)]*)\)", head).group(1).split()),
                                     "internaldate": re.search(r'INTERNALDATE "([^"]+)"', head).group(1),
                                     "size": re.search(r"RFC822\.SIZE (\d+)", head).group(1),
                                     "sha256": hashlib.sha256(part[1]).hexdigest()})
            state[f"{user} {name}"] = {"status": status[0].decode().split("(", 1)[1], "messages": messages}
        _, quota = imap.getquotaroot("INBOX")
        state[f"{user} quota"] = [item.decode() if isinstance(item, bytes) else str(item) for item in quota]
        imap.logout()
    return state


def count(state):
    return sum(len(box["messages"]) for key, box in state.items() if not key.endswith("quota"))


def main(run_dir, repo, pgbackrest, restic):
    run_dir.mkdir(parents=True)
    rng = random.Random(SEED)
    env = {**os.environ, "RESTIC_PASSWORD": os.urandom(24).hex(), "RESTIC_REPOSITORY": str(run_dir / "restic-repo")}
    cluster = Cluster(run_dir, pgbackrest / "pgbackrest")
    james = None
    try:
        cluster.init()
        cluster.start()
        cluster.sql("CREATE ROLE james SUPERUSER LOGIN PASSWORD 'secret1'")
        cluster.sql("CREATE DATABASE james_drill OWNER james")
        cluster.backrest("stanza-create")
        cluster.backrest("check")
        james = James(run_dir, repo, cluster)
        james.start()
        webadmin(james.state, "PUT", f"/domains/{DOMAIN}")
        for user, password in USERS.items():
            webadmin(james.state, "PUT", f"/users/{user}", {"password": password})
        print("drill: seeding before the backup", flush=True)
        seed(james, rng, 1, 60, 11)
        cluster.backrest("backup", "--type=full")
        print("drill: changing mail after the backup", flush=True)
        seed(james, rng, 1000, 25, 4)
        imap = james.imap(f"ana@{DOMAIN}")
        imap.select("INBOX")
        imap.uid("STORE", "1:5", "+FLAGS", "(\\Answered)")
        imap.logout()
        before = snapshot(james)
        james.stop()
        cluster.sql("SELECT pg_switch_wal()")
        cluster.backrest("check")
        info = json.loads(cluster.backrest("info", "--output=json").stdout)
        check("the backup and its WAL are in the repository, encrypted",
              info[0]["backup"] and info[0]["cipher"] == "aes-256-cbc", info[0]["status"]["message"])
        run(restic / "restic", "init", env=env)
        run(restic / "restic", "backup", "--quiet", cluster.repo, cluster.conf, james.home / "conf", env=env)
        cluster.stop()

        print("drill: the host is lost", flush=True)
        shutil.rmtree(cluster.data)
        shutil.rmtree(cluster.repo)
        shutil.rmtree(james.home / "conf")
        run(restic / "restic", "restore", "latest", "--target", "/", env=env)
        cluster.backrest("restore")
        started = time.monotonic()
        cluster.start()
        james.start()
        after = snapshot(james)
        restored_in = time.monotonic() - started

        messages = count(before)
        check("every mailbox, message and flag comes back, the mail changed after the backup included",
              after == before, f"{messages} messages in {len(before) - len(USERS)} mailboxes")
        check("every message's bytes are identical",
              all(a["sha256"] == b["sha256"] for key in before if not key.endswith("quota")
                  for a, b in zip(before[key]["messages"], after.get(key, {"messages": []})["messages"])))
        check("UIDVALIDITY, UIDNEXT and HIGHESTMODSEQ are the same, so a mail app resynchronises nothing",
              all(before[key]["status"] == after[key]["status"] for key in before if not key.endswith("quota")))
        check("the quota usage is the same", all(before[key] == after[key] for key in before if key.endswith("quota")))
        check("James comes back answering IMAP", restored_in < 300, f"{restored_in:.0f} s from PostgreSQL's start")
        james.stop()
        cluster.stop()
        cluster.backrest("restore", "--delta", "--type=immediate", "--target-action=promote")
        cluster.start()
        james.start()
        at_backup = snapshot(james)
        check("the control: restored to the backup alone, without the WAL after it, the later mail is missing",
              at_backup != before and count(at_backup) < count(before), f"{count(at_backup)} of {count(before)} messages")
        diffs = [key for key in before if before[key] != after.get(key)]
        if diffs:
            (run_dir / "before.json").write_text(json.dumps(before, indent=1))
            (run_dir / "after.json").write_text(json.dumps(after, indent=1))
            print("drill: differing:", ", ".join(diffs[:10]))
    finally:
        if james:
            james.stop()
        cluster.stop()
    print(f"{sum(results)} passed, {len(results) - sum(results)} failed of {len(results)}")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])))
