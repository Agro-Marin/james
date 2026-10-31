"""Run by `odoo-bin shell` (which provides `env`): the Odoo side of the end-to-end suite. E2E_ACTION names the action,
E2E_ARG its argument; the result is one JSON line after RESULT."""

import json
import os
import time

from odoo import Command

from odoo.addons.mail.tests.common import mail_new_test_user

DOMAIN = "agromarin.test"


def setup():
    domain = env["mail.alias.domain"].create({"name": DOMAIN, "bounce_alias": "bounce", "catchall_alias": "catchall"})
    env.company.alias_domain_id = domain
    env.ref("base.user_admin").group_ids += env.ref("credential.group_credential_user")
    maria = mail_new_test_user(env, login="maria", groups="base.group_user", name="Maria Orozco",
                               email=f"maria.orozco@{DOMAIN}")
    bea = mail_new_test_user(env, login="bea", groups="base.group_user", name="Bea Ventas", email=f"bea@{DOMAIN}")
    env["mail.inbox"].create({"name": "Ventas", "kind": "shared", "alias_name": "ventas", "alias_domain_id": domain.id,
                              "user_ids": [Command.set((maria | bea).ids)]})
    partner = env["res.partner"].create({"name": "Zed Supplier", "email": "zed@supplier.test"})
    note = partner.message_post(body="Cotizacion enviada", message_type="comment", subtype_xmlid="mail.mt_comment")
    server = env["fetchmail.server"].create({"name": "James edge", "server_type": "local"})
    env.cr.commit()
    action = env(user=env.ref("base.user_admin"))["fetchmail.server"].browse(server.id).action_issue_inbound_token()
    env.cr.commit()
    return {"server_id": server.id, "token": action["params"]["message"].split("\n", 1)[0].strip(),
            "partner_id": partner.id, "note_message_id": note.message_id}


def create_inbox(name):
    domain = env["mail.alias.domain"].search([("name", "=", DOMAIN)])
    env["mail.inbox"].create({"name": name.title(), "kind": "shared", "alias_name": name, "alias_domain_id": domain.id,
                              "user_ids": [Command.set(env.ref("base.user_admin").ids)]})
    env.cr.commit()
    return {"committed_at": time.time()}


def archive_inbox(name):
    env["mail.inbox"].search([("alias_name", "=", name)]).action_archive()
    env.cr.commit()
    return {"committed_at": time.time()}


ACTIONS = {"setup": setup, "create_inbox": create_inbox, "archive_inbox": archive_inbox}
action = ACTIONS[os.environ["E2E_ACTION"]]
result = action(os.environ["E2E_ARG"]) if os.environ.get("E2E_ARG") else action()
print("RESULT " + json.dumps(result), flush=True)
