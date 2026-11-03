# The fork's deployment configuration

Laid over `server/apps/postgres-app/sample-configuration` (and `sample-configuration-single`), these files make James
the edge of Odoo's mail:

- `odoo.properties`: where James reads the addresses Odoo answers. Its presence installs `OdooModule`.
- `smtpserver.xml`: `OdooValidRcptHandler` in place of `ValidRcptHandler`, so RCPT accepts an Odoo address, refuses
  an address neither James nor Odoo answers, and defers one both answer (a configuration error, logged as an error).
  On 587, Odoo's outgoing mail server signs in as `odoo@agromarin.mx` (`ConfigurationAuthHook`), which may send as
  any address; people sign in with their own passwords (`UsersRepositoryAuthHook`, needed once any AuthHook is
  configured).
- `rspamd.properties`: where Rspamd listens. On port 25, `RspamdSMTPHook` asks it about each message at the end of
  DATA: what it rejects (spam, GTUBE, a sender failing a DMARC reject policy) is refused with 554 5.7.1, what it
  defers gets 451 4.7.1, and when it cannot answer the message is accepted.
  Mail it accepted for a mailbox is scanned again before delivery (`RspamdScanner`), and what Rspamd marks as spam
  is filed in the Spam folder.
- `mailetcontainer.xml`: in `transport`, mail waits while Odoo's addresses were never read (`OdooDirectoryUnknown`),
  and an Odoo address goes to `odoo-deliver` (`RecipientIsOdoo`), where `OdooInbound` posts it to Odoo's
  `/mail/inbound/<server id>`. A deferral is retried every 5 minutes for a day, then bounced.
  In `relay`, mail whose envelope sender is in a James domain (people's, and Odoo's `bounce@`) is DKIM-signed with
  `conf/dkim-james1.pem` (PKCS#8 PEM; DNS: `james1._domainkey.<domain>` TXT `v=DKIM1; k=rsa; p=<public key>`), then
  handed to SES's SMTP endpoint, retried for about 30 hours.

The OdooInbound token is the one Odoo's local fetchmail server issues ("Issue Inbound Token"), in
`conf/odoo-inbound.token`, readable by James only. `marin/odoo-e2e/run.sh` runs this configuration against a real Odoo.

The edge listens on 25 (SMTP, STARTTLS), 587 (submission, STARTTLS and authentication required) and 993 (IMAPS);
465 and 143 are closed, and JMAP listens on the loopback for nginx. Every TLS port offers TLS 1.3 and 1.2 with
forward-secret AEAD ciphers only, and a client may not renegotiate (`jvm.properties`): `marin/tls-check/run.sh` holds
it with testssl.sh. On 25 a client has 20 connections, a message 100 recipients and 40 MB; on 587, 50 connections,
200 recipients and 40 MB.
