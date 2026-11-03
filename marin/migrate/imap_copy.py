"""Copies one mailbox's history from an IMAP server (WorkMail) into James, and verifies the copy.

Each source folder goes to its James counterpart (WorkMail's "Sent Items" to Sent, and so on), each message with its
flags and internal date. A message the destination folder already holds is not copied again, so a rerun copies only
what arrived since: the nightly sync of residual mail after the MX switch is this command run again. A message
marked \\Deleted and not yet expunged is not copied. James is written as an administrator acting for the user (SASL
PLAIN with the user as authorization identity; usersrepository.xml's administratorIds).

usage: imap_copy.py copy|verify <config.json>
  config: {"source": {"host", "port", "tls", "user", "password"},
           "destination": {"host", "port", "tls", "user", "admin", "password"},
           "deep": false}
The report, one JSON object per folder, goes to stdout; the exit status is 0 when every folder matches."""

import collections
import hashlib
import imaplib
import json
import re
import sys

SPECIAL_USE = {"\\Sent": "Sent", "\\Trash": "Trash", "\\Junk": "Spam", "\\Drafts": "Drafts", "\\Archive": "Archive"}
WORKMAIL_NAMES = {"Sent Items": "Sent", "Deleted Items": "Trash", "Junk E-mail": "Spam", "Junk Email": "Spam",
                  "Drafts": "Drafts", "Archive": "Archive"}
SKIPPED_FOLDERS = {"Outbox"}
KEPT_FLAGS = ("\\Seen", "\\Answered", "\\Flagged", "\\Draft")
LIST_LINE = re.compile(rb'^\((?P<attributes>[^)]*)\) (?P<delimiter>"[^"]*"|NIL) (?P<name>.+)$')
BATCH = 200


def connect(server):
    imap = imaplib.IMAP4_SSL(server["host"], server["port"]) if server.get("tls") else imaplib.IMAP4(
        server["host"], server["port"])
    return imap


def login_source(server):
    imap = connect(server)
    imap.login(server["user"], server["password"])
    return imap


def login_destination(server):
    imap = connect(server)
    credentials = f"{server['user']}\0{server['admin']}\0{server['password']}".encode()
    imap.authenticate("PLAIN", lambda _challenge: credentials)
    return imap


def unquote(value):
    value = value.strip()
    if value.startswith(b'"') and value.endswith(b'"'):
        return value[1:-1].replace(b'\\"', b'"').replace(b"\\\\", b"\\")
    return value


def folders(imap):
    _, lines = imap.list()
    found = []
    for line in lines:
        if isinstance(line, tuple):
            line = line[0] + b'"' + line[1] + b'"'
        match = LIST_LINE.match(line)
        if not match:
            continue
        attributes = match["attributes"].decode().split()
        delimiter = match["delimiter"].strip(b'"').decode() if match["delimiter"] != b"NIL" else None
        found.append((unquote(match["name"]).decode(), delimiter, attributes))
    return found


def destination_name(name, delimiter, attributes):
    if name.upper() == "INBOX":
        return "INBOX"
    for attribute in attributes:
        if attribute in SPECIAL_USE:
            return SPECIAL_USE[attribute]
    parts = name.split(delimiter) if delimiter else [name]
    parts[0] = WORKMAIL_NAMES.get(parts[0], parts[0])
    return ".".join(part.replace(".", "_") for part in parts)


def quoted(name):
    return '"' + name.replace("\\", "\\\\").replace('"', '\\"') + '"'


def inventory(imap, folder):
    """The folder's messages as {key: [(uid, size, flags, internaldate)]}, a key being Message-ID and size."""
    typ, _ = imap.select(quoted(folder), readonly=True)
    if typ != "OK":
        return None
    messages = collections.defaultdict(list)
    _, data = imap.uid("SEARCH", None, "ALL")
    uids = data[0].split()
    for start in range(0, len(uids), BATCH):
        chunk = b",".join(uids[start:start + BATCH])
        _, fetched = imap.uid("FETCH", chunk,
                              "(RFC822.SIZE FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)])")
        for part in fetched:
            if not isinstance(part, tuple):
                continue
            head = part[0].decode(errors="replace")
            uid = re.search(r"UID (\d+)", head).group(1)
            size = int(re.search(r"RFC822\.SIZE (\d+)", head).group(1))
            flags = re.search(r"FLAGS \(([^)]*)\)", head).group(1).split()
            internaldate = re.search(r'INTERNALDATE "([^"]+)"', head).group(1)
            message_id = re.sub(rb"(?i)^message-id:\s*|\s+", b"", part[1]).decode(errors="replace")
            key = (message_id or f"no-id {internaldate}", size)
            messages[key].append((uid, size, flags, internaldate))
    return messages


def body(imap, uid):
    _, fetched = imap.uid("FETCH", uid, "(BODY.PEEK[])")
    return next(part[1] for part in fetched if isinstance(part, tuple))


def copy_folder(source, destination, name, target):
    held = inventory(source, name)
    if held is None:
        return {"folder": name, "target": target, "error": "cannot select the source folder"}
    if destination.select(quoted(target))[0] != "OK":
        destination.create(quoted(target))
    present = inventory(destination, target) or {}
    copied = skipped = 0
    for key, messages in held.items():
        missing = len(messages) - len(present.get(key, []))
        for uid, _size, flags, internaldate in messages:
            if missing <= 0:
                break
            if "\\Deleted" in flags:
                skipped += 1
                continue
            kept = " ".join(flag for flag in flags if flag in KEPT_FLAGS or not flag.startswith("\\"))
            typ, data = destination.append(quoted(target), f"({kept})", f'"{internaldate}"',
                                           body(source, uid))
            if typ != "OK":
                raise RuntimeError(f"APPEND to {target} refused: {data}")
            copied += 1
            missing -= 1
    return {"folder": name, "target": target, "copied": copied, "skipped_deleted": skipped}


def compare_folder(source, destination, name, target, deep):
    held, present = inventory(source, name), inventory(destination, target) or {}
    wanted = {key: [m for m in messages if "\\Deleted" not in m[2]] for key, messages in (held or {}).items()}
    report = {"folder": name, "target": target,
              "source_messages": sum(len(m) for m in wanted.values()),
              "source_bytes": sum(m[1] for messages in wanted.values() for m in messages),
              "destination_messages": sum(len(m) for m in present.values()),
              "destination_bytes": sum(m[1] for messages in present.values() for m in messages)}
    missing = sum(max(0, len(messages) - len(present.get(key, []))) for key, messages in wanted.items())
    flags_differ = sum(1 for key, messages in wanted.items() for a, b in zip(sorted(messages, key=lambda m: m[3]),
                                                                           sorted(present.get(key, []), key=lambda m: m[3]))
                       if sorted(f for f in a[2] if f in KEPT_FLAGS) != sorted(f for f in b[2] if f in KEPT_FLAGS))
    report.update(missing=missing, flags_differ=flags_differ)
    if deep:
        differ = 0
        for key, messages in wanted.items():
            for a, b in zip(messages, present.get(key, [])):
                if hashlib.sha256(body(source, a[0])).digest() != hashlib.sha256(body(destination, b[0])).digest():
                    differ += 1
        report["bytes_differ"] = differ
    report["matches"] = (missing == 0 and flags_differ == 0 and report.get("bytes_differ", 0) == 0
                         and report["destination_messages"] >= report["source_messages"])
    return report


def main(command, config_path):
    with open(config_path) as config_file:
        config = json.load(config_file)
    source = login_source(config["source"])
    destination = login_destination(config["destination"])
    status = 0
    for name, delimiter, attributes in folders(source):
        if "\\Noselect" in attributes or "\\NonExistent" in attributes or name in SKIPPED_FOLDERS:
            continue
        target = destination_name(name, delimiter, attributes)
        if command == "copy":
            report = copy_folder(source, destination, name, target)
        else:
            report = compare_folder(source, destination, name, target, config.get("deep", False))
            status |= 0 if report["matches"] else 1
        print(json.dumps(report, ensure_ascii=False), flush=True)
    source.logout()
    destination.logout()
    return status


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("copy", "verify"):
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
