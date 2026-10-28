"""Starts and stops the postgres-app James that imaptest runs against, configured from the app's sample configuration."""

import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DOMAIN = "lane.test"
USER = f"imaptest@{DOMAIN}"
USER_PASSWORD = "imaptest-pass"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def edit(path, *pairs):
    text = path.read_text()
    for pattern, replacement in pairs:
        text, count = re.subn(pattern, replacement, text)
        if count != 1:
            raise SystemExit(f"james.py: {path.name}: {pattern!r} matched {count} times, expected 1")
    path.write_text(text)


def configure(home, app, pg_port, database):
    conf = home / "conf"
    shutil.copytree(app / "sample-configuration", conf)
    for overlay in (app / "sample-configuration-single").iterdir():
        shutil.copy(overlay, conf / overlay.name)
    ports = {"imap": free_port(), "webadmin": free_port()}
    password = secrets.token_urlsafe(24)
    edit(
        conf / "postgres.properties",
        (r"database\.name=james", f"database.name={database}"),
        (r"database\.host=postgres", "database.host=127.0.0.1"),
        (r"database\.port=5432", f"database.port={pg_port}"),
        (r"ssl\.mode=allow", "ssl.mode=disable"),
    )
    edit(
        conf / "imapserver.xml",
        (r"<bind>0\.0\.0\.0:143</bind>", f"<bind>127.0.0.1:{ports['imap']}</bind>"),
        (r'<imapserver enabled="true">(\s*<jmxName>imapserver-ssl</jmxName>)', r'<imapserver enabled="false">\1'),
    )
    smtp = conf / "smtpserver.xml"
    smtp.write_text(smtp.read_text().replace('<smtpserver enabled="true">', '<smtpserver enabled="false">'))
    edit(conf / "jmap.properties", (r"(?m)^enabled=true$", "enabled=false"))
    edit(conf / "jmx.properties", (r"(?m)^jmx\.enabled=true$", "jmx.enabled=false"))
    edit(
        conf / "webadmin.properties",
        (r"(?m)^port=8000$", f"port={ports['webadmin']}"),
        (r"(?m)^password\.generate=true$", f"password={password}"),
    )
    logs = home / "logs"
    logs.mkdir()
    edit(
        conf / "logback.xml",
        (r"<file>/logs/james\.log</file>", f"<file>{logs}/james.log</file>"),
        (r"<fileNamePattern>/logs/", f"<fileNamePattern>{logs}/"),
    )
    subprocess.run(
        ["keytool", "-genkeypair", "-alias", "james", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
         "-keystore", str(conf / "keystore"), "-storepass", "james72laBalle", "-keypass", "james72laBalle",
         "-dname", f"CN=imap.{DOMAIN}", "-validity", "2"],
        check=True, capture_output=True,
    )
    return ports, password


def webadmin(state, method, path, body=None):
    request = urllib.request.Request(
        f"http://127.0.0.1:{state['ports']['webadmin']}{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Password": state["password"], "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status


def wait_until_up(state, process, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise SystemExit(f"james.py: James exited with {process.returncode}; see {state['home']}/james.out")
        try:
            if webadmin(state, "GET", "/healthcheck") == 200:
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(1)
    raise SystemExit(f"james.py: James did not answer within {seconds}s; see {state['home']}/james.out")


def start(run, repo, pg_port, database):
    home = run / "james"
    app = repo / "server" / "apps" / "postgres-app"
    home.mkdir()
    shutil.copy(app / "target" / "james-server-postgres-app.jar", home)
    shutil.copytree(app / "target" / "james-server-postgres-app.lib", home / "james-server-postgres-app.lib")
    ports, password = configure(home, app, pg_port, database)
    with (home / "james.out").open("w") as out:
        process = subprocess.Popen(
            ["java", "-Xms256m", "-Xmx1g", "-Dworking.directory=.", "-Dlogback.configurationFile=conf/logback.xml",
             "-Djdk.tls.ephemeralDHKeySize=2048", "-Dextra.props=conf/jvm.properties",
             "-jar", "james-server-postgres-app.jar"],
            cwd=home, stdout=out, stderr=subprocess.STDOUT, start_new_session=True,
        )
    state = {"home": str(home), "pid": process.pid, "ports": ports, "password": password,
             "user": USER, "user_password": USER_PASSWORD}
    (run / "james.json").write_text(json.dumps(state))
    wait_until_up(state, process)
    webadmin(state, "PUT", f"/domains/{DOMAIN}")
    webadmin(state, "PUT", f"/users/{USER}", {"password": USER_PASSWORD})
    print(json.dumps({"imap_port": ports["imap"], "user": USER, "password": USER_PASSWORD}))


def stop(run):
    state_file = run / "james.json"
    if not state_file.exists():
        return
    pid = json.loads(state_file.read_text())["pid"]
    try:
        os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    for _ in range(60):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(1)
    os.killpg(pid, signal.SIGKILL)


if __name__ == "__main__":
    if sys.argv[1] == "start":
        start(Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4]), sys.argv[5])
    elif sys.argv[1] == "stop":
        stop(Path(sys.argv[2]))
    else:
        raise SystemExit("usage: james.py start <run> <repo> <pg-port> <database> | stop <run>")
