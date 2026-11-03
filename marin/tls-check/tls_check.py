"""testssl.sh against the deployment configuration's TLS ports (marin/conf): SMTP 25 and submission 587 with STARTTLS,
IMAPS 993. James runs with a self-signed certificate, so the certificate's trust findings are the deployed host's
(Let's Encrypt) to settle; every other finding at LOW or above fails the check.

usage: tls_check.py <run dir> <james repo> <testssl.sh>"""

import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lane"))
from james import edit, free_port  # noqa: E402

PG_BINDIR = Path(os.environ.get("PG_BINDIR", "/usr/lib/postgresql/18/bin"))
SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
# What a self-signed certificate on 127.0.0.1 cannot pass, and Let's Encrypt on mail.agromarin.mx settles.
CERTIFICATE_FINDINGS = re.compile(r"^(cert_.*|chain_of_trust|cert_chain_of_trust|cert_trust|cert_commonName.*|"
                                  r"cert_subjectAltName|cert_caIssuers|cert_crlDistributionPoints|cert_ocspURL|"
                                  r"OCSP_stapling|cert_mustStapleExtension|cert_certificatePolicies_EV|DNS_CAArecord|"
                                  r"cert_extlifeSpan|cert_validityPeriod|cert_expirationStatus|cert_notAfter|"
                                  r"cert_revocation|cert_ocspRevoked|cert_crlRevoked|certificate_transparency|"
                                  r"cert_numbers|intermediate_cert.*)$")

# Findings accepted, with why. JDK 21 offers no hybrid post-quantum key exchange (ML-KEM) in TLS 1.3.
ACCEPTED = {"FS_KEMs": "no post-quantum key exchange before a JDK that offers it"}

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}", flush=True)


def run(*command, **kwargs):
    return subprocess.run([str(part) for part in command], check=True, capture_output=True, text=True, **kwargs)


def start(run_dir, repo):
    pg, pg_port = run_dir / "pg", free_port()
    run(PG_BINDIR / "initdb", "-D", pg, "-U", "postgres", "--auth-local=trust", "--auth-host=scram-sha-256",
        "--pwfile=/dev/stdin", "-E", "UTF8", "--locale=C", input="postgres\n")
    run(PG_BINDIR / "pg_ctl", "-D", pg, "-l", run_dir / "pg.log", "-w", "start", "-o",
        f"-p {pg_port} -k {run_dir} -c listen_addresses=127.0.0.1")
    for statement in ("CREATE ROLE james SUPERUSER LOGIN PASSWORD 'secret1'", "CREATE DATABASE james_tls OWNER james"):
        run("psql", "-h", run_dir, "-p", pg_port, "-U", "postgres", "-d", "postgres", "-qc", statement)
    home = run_dir / "james"
    home.mkdir()
    app = repo / "server" / "apps" / "postgres-app"
    (home / "james-server-postgres-app.jar").symlink_to(app / "target" / "james-server-postgres-app.jar")
    (home / "james-server-postgres-app.lib").symlink_to(app / "target" / "james-server-postgres-app.lib")
    conf = home / "conf"
    shutil.copytree(app / "sample-configuration", conf)
    for overlay in [*(app / "sample-configuration-single").iterdir(), *(repo / "marin" / "conf").iterdir()]:
        if overlay.name != "README.md":
            shutil.copy(overlay, conf / overlay.name)
    ports = {"smtp": free_port(), "submission": free_port(), "imaps": free_port(), "webadmin": free_port(),
             "jmap": free_port()}
    smtp = conf / "smtpserver.xml"
    smtp.write_text(smtp.read_text().replace("<bind>0.0.0.0:25</bind>", f"<bind>127.0.0.1:{ports['smtp']}</bind>")
                    .replace("<bind>0.0.0.0:587</bind>", f"<bind>127.0.0.1:{ports['submission']}</bind>")
                    .replace("<bind>0.0.0.0:465</bind>", f"<bind>127.0.0.1:{free_port()}</bind>"))
    imap = conf / "imapserver.xml"
    imap.write_text(imap.read_text().replace("<bind>0.0.0.0:993</bind>", f"<bind>127.0.0.1:{ports['imaps']}</bind>")
                    .replace("<bind>0.0.0.0:143</bind>", f"<bind>127.0.0.1:{free_port()}</bind>"))
    edit(conf / "webadmin.properties", (r"(?m)^port=8000$", f"port={ports['webadmin']}"))
    with (conf / "jmap.properties").open("a") as jmap:
        jmap.write(f"\njmap.port={ports['jmap']}\n")
    edit(conf / "jmx.properties", (r"(?m)^jmx\.enabled=true$", "jmx.enabled=false"))
    site, logs = home / "site", home / "logs"
    site.mkdir()
    logs.mkdir()
    run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", site / "dkim-james1.pem")
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", site / "privkey.pem", "-out",
        site / "fullchain.pem", "-days", "2", "-subj", "/CN=mail.agromarin.test")
    environment = {
        "JAMES_PG_PORT": str(pg_port), "JAMES_PG_DATABASE": "james_tls", "JAMES_PG_PASSWORD": "secret1",
        "JAMES_ODOO_DATABASE_URL": "jdbc:postgresql://127.0.0.1:1/none", "JAMES_ODOO_DATABASE_PASSWORD": "none",
        "JAMES_ODOO_INBOUND_URL": "http://127.0.0.1:1/mail/inbound/1",
        "JAMES_ODOO_INBOUND_TOKEN_FILE": str(site / "odoo-inbound.token"), "JAMES_ODOO_SMTP_PASSWORD": "tls-check",
        "JAMES_DKIM_KEY": f"file://{site}/dkim-james1.pem", "JAMES_SES_HOST": "127.0.0.1", "JAMES_SES_USERNAME": "",
        "JAMES_SES_PASSWORD": "", "JAMES_TLS_KEY": f"file://{site}/privkey.pem",
        "JAMES_TLS_CERTIFICATES": f"file://{site}/fullchain.pem", "JAMES_WEBADMIN_PASSWORD": "tls-check",
        "JAMES_LOG_DIR": str(logs), "JAMES_DEFAULT_DOMAIN": "agromarin.test",
    }
    with (home / "james.out").open("w") as out:
        process = subprocess.Popen(
            ["java", "-Xms256m", "-Xmx1g", "-Dworking.directory=.", "-Dlogback.configurationFile=conf/logback.xml",
             "-Djdk.tls.ephemeralDHKeySize=2048", "-Dextra.props=conf/jvm.properties", "-jar",
             "james-server-postgres-app.jar"], cwd=home, stdout=out, stderr=subprocess.STDOUT, start_new_session=True,
            env={**os.environ, **environment})
    # James's health check stays unhealthy here, with no Odoo behind it: wait for the ports instead.
    deadline = time.monotonic() + 180
    while not all(listening(ports[name]) for name in ("smtp", "submission", "imaps")):
        if process.poll() is not None or time.monotonic() > deadline:
            raise SystemExit(f"tls_check.py: James did not open its ports; see {home}/james.out")
        time.sleep(1)
    return pg, process, ports


def listening(port):
    with socket.socket() as probe:
        return probe.connect_ex(("127.0.0.1", port)) == 0


def findings(run_dir, testssl, name, port, starttls):
    report = run_dir / f"{name}.json"
    command = [testssl, "--quiet", "--color", "0", "--warnings", "off", "--sneaky", "--jsonfile", report,
               "-p", "-s", "-f", "-E", "-U", "-S"]
    if starttls:
        command += ["--starttls", starttls]
    subprocess.run([str(part) for part in command] + [f"127.0.0.1:{port}"], capture_output=True, text=True,
                   timeout=1800)
    entries = json.loads(report.read_text())
    flagged = [entry for entry in entries if entry.get("severity") in SEVERITIES and entry["id"] not in ACCEPTED]
    return ([entry for entry in flagged if not CERTIFICATE_FINDINGS.match(entry["id"])],
            [entry for entry in flagged if CERTIFICATE_FINDINGS.match(entry["id"])],
            [entry for entry in entries if entry["id"] in ("TLS1_3", "TLS1_2", "TLS1_1", "TLS1", "SSLv3", "SSLv2")])


def main(run_dir, repo, testssl):
    run_dir.mkdir(parents=True)
    pg, process, ports = start(run_dir, repo)
    try:
        for name, port, starttls in (("smtp-25", ports["smtp"], "smtp"), ("submission-587", ports["submission"], "smtp"),
                                     ("imaps-993", ports["imaps"], None)):
            failing, certificate, protocols = findings(run_dir, testssl, name, port, starttls)
            offered = {entry["id"]: entry["finding"] for entry in protocols}
            print(f"{name}: " + ", ".join(f"{key} {value}" for key, value in offered.items()), flush=True)
            check(f"{name} has no finding at LOW or above but its self-signed certificate's",
                  not failing and offered.get("TLS1_3", "").startswith("offered"),
                  "; ".join(f"{entry['id']} {entry['severity']}: {entry['finding']}" for entry in failing)[:900])
            check(f"{name} offers TLS 1.3 and 1.2 only", offered.get("TLS1_3", "").startswith("offered")
                  and offered.get("TLS1_2", "").startswith("offered")
                  and all("not offered" in offered.get(old, "not offered") for old in ("TLS1_1", "TLS1", "SSLv3", "SSLv2")))
            print(f"{name}: left to the deployed certificate: " + ", ".join(sorted({e['id'] for e in certificate})))
    finally:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(60)
        subprocess.run([PG_BINDIR / "pg_ctl", "-D", pg, "-m", "fast", "stop"], capture_output=True)
    print(f"{sum(results)} passed, {len(results) - sum(results)} failed of {len(results)}")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])))
