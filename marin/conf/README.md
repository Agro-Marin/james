# The fork's deployment configuration

Laid over `server/apps/postgres-app/sample-configuration` (and `sample-configuration-single`), these files make James
the edge of Odoo's mail:

- `odoo.properties`: where James reads the addresses Odoo answers. Its presence installs `OdooModule`.
- `smtpserver.xml`: `OdooValidRcptHandler` in place of `ValidRcptHandler`, so RCPT accepts an Odoo address, refuses
  an address neither James nor Odoo answers, and defers one both answer (a configuration error, logged as an error).
- `mailetcontainer.xml`: in `transport`, mail waits while Odoo's addresses were never read (`OdooAddressesUnknown`),
  and an Odoo address goes to `odoo-deliver` (`RecipientIsOdoo`), where `OdooInbound` posts it to Odoo's
  `/mail/inbound/<server id>`. A deferral is retried every 5 minutes for a day, then bounced.

The OdooInbound token is the one Odoo's local fetchmail server issues ("Issue Inbound Token"), in
`conf/odoo-inbound.token`, readable by James only. `marin/odoo-e2e/run.sh` runs this configuration against a real Odoo.
