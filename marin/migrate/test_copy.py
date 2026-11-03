"""imap_copy.py against a James holding both sides: a source mailbox laid out as WorkMail's (its folder names, a
nested and an accented folder, flags, keywords, a message without Message-ID, a duplicate, a message marked deleted)
copied into another user's mailbox through an administrator, then copied again, and again after new mail arrived.

usage: test_copy.py <run dir> <james repo>"""

import imaplib
import json
import os
import random
import signal
import subprocess
import sys
from email.message import EmailMessage
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "lane"))
from james import configure, edit, free_port, wait_until_up, webadmin  # noqa: E402

PG_BINDIR = Path(os.environ.get("PG_BINDIR", "/usr/lib/postgresql/18/bin"))
DOMAIN = "lane.test"
SOURCE, DESTINATION, ADMIN = f"workmail.maria@{DOMAIN}", f"maria@{DOMAIN}", f"migracion@{DOMAIN}"
PASSWORDS = {SOURCE: "source-pass", DESTINATION: "maria-pass", ADMIN: "admin-pass"}
FOLDERS = {"INBOX": 30, "Sent Items": 12, "Deleted Items": 4, "Junk E-mail": 3, "Drafts": 2, "Clientes": 3,
           "Clientes.Agro 2026": 6, "Facturaci&APM-n": 5}

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}", flush=True)


def run(*command, **kwargs):
    return subprocess.run([str(part) for part in command], check=True, capture_output=True, text=True, **kwargs)


def message(rng, folder, number, message_id=True):
    mail = EmailMessage()
    mail["From"] = f"Cliente {number} <c{number}@cliente.test>"
    mail["To"] = SOURCE
    mail["Subject"] = f"{folder} {number}: cotización"
    if message_id:
        mail["Message-Id"] = f"<{folder.replace(' ', '-')}-{number}@cliente.test>"
    mail.set_content("Buenas tardes\r\n" * rng.randint(1, 50))
    if number % 6 == 0:
        mail.add_attachment(rng.randbytes(rng.randint(5_000, 150_000)), maintype="application", subtype="pdf",
                            filename=f"doc-{number}.pdf")
    return mail.as_bytes()


def imap(port, user):
    session = imaplib.IMAP4("127.0.0.1", port)
    session.login(user, PASSWORDS[user])
    return session


def seed(port, rng, first, count_scale=1):
    session = imap(port, SOURCE)
    for folder, count in FOLDERS.items():
        session.create(f'"{folder}"')
        for number in range(first, first + count * count_scale):
            flags = "(\\Seen \\Answered)" if number % 4 == 0 else "(\\Flagged $Importante)" if number % 5 == 0 else "()"
            session.append(f'"{folder}"', flags, imaplib.Time2Internaldate(1780000000 + number * 7200),
                           message(rng, folder, number, message_id=number != 3))
    return session


def copy(config, command="copy"):
    out = subprocess.run([sys.executable, "-I", "-B", str(HERE / "imap_copy.py"), command, str(config)],
                         capture_output=True, text=True)
    reports = [json.loads(line) for line in out.stdout.splitlines() if line.startswith("{")]
    return out.returncode, reports, out.stderr


def main(run_dir, repo):
    run_dir.mkdir(parents=True)
    rng = random.Random(20261103)
    pg, pg_port = run_dir / "pg", free_port()
    run(PG_BINDIR / "initdb", "-D", pg, "-U", "postgres", "--auth-local=trust", "--auth-host=scram-sha-256",
        "--pwfile=/dev/stdin", "-E", "UTF8", "--locale=C", input="postgres\n")
    run(PG_BINDIR / "pg_ctl", "-D", pg, "-l", run_dir / "pg.log", "-w", "start", "-o",
        f"-p {pg_port} -k {run_dir} -c listen_addresses=127.0.0.1")
    process = None
    try:
        for statement in ("CREATE ROLE james SUPERUSER LOGIN PASSWORD 'secret1'", "CREATE DATABASE james_migrate OWNER james"):
            run("psql", "-h", run_dir, "-p", pg_port, "-U", "postgres", "-d", "postgres", "-qc", statement)
        home = run_dir / "james"
        home.mkdir()
        app = repo / "server" / "apps" / "postgres-app"
        (home / "james-server-postgres-app.jar").symlink_to(app / "target" / "james-server-postgres-app.jar")
        (home / "james-server-postgres-app.lib").symlink_to(app / "target" / "james-server-postgres-app.lib")
        ports, password = configure(home, app, pg_port, "james_migrate")
        edit(home / "conf" / "usersrepository.xml", (r"</usersrepository>",
             f"    <administratorIds><administratorId>{ADMIN}</administratorId></administratorIds>\n</usersrepository>"))
        with (home / "james.out").open("w") as out:
            process = subprocess.Popen(
                ["java", "-Xms256m", "-Xmx1g", "-Dworking.directory=.", "-Dlogback.configurationFile=conf/logback.xml",
                 "-Dextra.props=conf/jvm.properties", "-jar", "james-server-postgres-app.jar"],
                cwd=home, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        state = {"home": str(home), "ports": ports, "password": password}
        wait_until_up(state, process)
        webadmin(state, "PUT", f"/domains/{DOMAIN}")
        for user, user_password in PASSWORDS.items():
            webadmin(state, "PUT", f"/users/{user}", {"password": user_password})
        port = ports["imap"]

        source = seed(port, rng, 1)
        source.append("INBOX", "()", imaplib.Time2Internaldate(1780000000), message(rng, "INBOX", 1))
        source.select("INBOX")
        source.uid("STORE", "2", "+FLAGS", "(\\Deleted)")
        source.logout()
        config = run_dir / "copy.json"
        config.write_text(json.dumps({
            "source": {"host": "127.0.0.1", "port": port, "user": SOURCE, "password": PASSWORDS[SOURCE]},
            "destination": {"host": "127.0.0.1", "port": port, "user": DESTINATION, "admin": ADMIN,
                            "password": PASSWORDS[ADMIN]},
            "deep": True}))

        status, reports, errors = copy(config)
        by_folder = {report["folder"]: report for report in reports}
        check("the first copy runs over every folder", status == 0 and set(FOLDERS) <= set(by_folder), errors[-300:])
        check("WorkMail's folders land in James's", [by_folder[f]["target"] for f in
                                                    ("Sent Items", "Deleted Items", "Junk E-mail", "Clientes.Agro 2026")]
              == ["Sent", "Trash", "Spam", "Clientes.Agro 2026"], json.dumps(reports, ensure_ascii=False)[:300])
        check("a message marked deleted is not copied", by_folder["INBOX"].get("skipped_deleted") == 1)
        status, reports, errors = copy(config, "verify")
        check("verify: every folder's messages, flags and bytes match the source",
              status == 0 and all(report["matches"] for report in reports),
              json.dumps([r for r in reports if not r["matches"]], ensure_ascii=False)[:400] or errors[-300:])
        inbox = next(report for report in reports if report["folder"] == "INBOX")
        check("the duplicate and the message without Message-ID are copied too",
              inbox["destination_messages"] == FOLDERS["INBOX"] + 1 - 1, json.dumps(inbox))

        status, reports, _ = copy(config)
        check("a second copy copies nothing", status == 0 and sum(report["copied"] for report in reports) == 0,
              str([report["copied"] for report in reports]))

        residual = imap(port, SOURCE)
        for number in range(500, 503):
            residual.append("INBOX", "()", imaplib.Time2Internaldate(1790000000 + number), message(rng, "INBOX", number))
        residual.logout()
        status, reports, _ = copy(config)
        check("the nightly sync copies only the mail that arrived since", status == 0
              and {report["folder"]: report["copied"] for report in reports if report["copied"]} == {"INBOX": 3},
              str({report["folder"]: report["copied"] for report in reports}))
        status, reports, _ = copy(config, "verify")
        check("verify after the sync matches", status == 0 and all(report["matches"] for report in reports))

        lost = imap(port, DESTINATION)
        lost.select("Sent")
        lost.uid("STORE", "1", "+FLAGS", "(\\Deleted)")
        lost.expunge()
        lost.logout()
        status, reports, _ = copy(config, "verify")
        sent = next(report for report in reports if report["folder"] == "Sent Items")
        check("the control: verify reports a message lost from the destination", status == 1 and sent["missing"] == 1,
              json.dumps(sent))
        status, reports, _ = copy(config)
        status, reports, _ = copy(config, "verify")
        check("and copying again restores it", status == 0 and all(report["matches"] for report in reports))

        maria = imap(port, DESTINATION)
        maria.select('"Facturaci&APM-n"', readonly=True)
        _, data = maria.search(None, "ALL")
        check("Maria reads the accented folder herself", len(data[0].split()) == FOLDERS["Facturaci&APM-n"])
        maria.select("INBOX", readonly=True)
        _, data = maria.search(None, "KEYWORD", "$Importante")
        check("keywords survive", len(data[0].split()) > 0)
        maria.logout()
        stranger = imaplib.IMAP4("127.0.0.1", port)
        try:
            stranger.authenticate("PLAIN", lambda _: f"{DESTINATION}\0{SOURCE}\0{PASSWORDS[SOURCE]}".encode())
            refused = False
        except imaplib.IMAP4.error:
            refused = True
        check("a user who is no administrator cannot act for another", refused)
    finally:
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(60)
        subprocess.run([PG_BINDIR / "pg_ctl", "-D", pg, "-m", "fast", "stop"], capture_output=True)
    print(f"{sum(results)} passed, {len(results) - sum(results)} failed of {len(results)}")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2])))
