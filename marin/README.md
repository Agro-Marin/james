# Agro-Marin's James

The fork of Apache James that runs Agro-Marin's mail: the `postgres-app`, people's mailboxes over IMAP, and team
addresses delivered into Odoo. Branch `3.10-marin`, from upstream `master` at `de9f3955dc`. There is no upstream
merge: an upstream fix is re-implemented by hand. The plan is `agromarin-knowledge` `plans/2026-10-27-james-mail-server-plan.md`.

## The lane

`marin/lane.sh` is the gate for every commit. Nothing lands red.

| Stage | What runs |
|---|---|
| `build` | `install` of the tested modules and `server/apps/postgres-app`, checkstyle on, JDK 21 |
| `junit` | 14 modules that need no Docker, `TZ=UTC` |
| `pg` | 5 PostgreSQL modules against a throwaway PostgreSQL 18 (`-Djames.postgres.external`), `TZ=UTC` |
| `tz` | the date-sensitive modules again at America/Mexico_City and America/Monterrey |
| `imap` | Dovecot's `imaptest` against the postgres-app: the scripted suite, then 10 clients for 60 s |

`marin/lane.sh junit pg` runs only those stages. Every run also checks that each `@Disabled` or unstable test of
ours has a verdict in `excluded-tests.json`.

`floors.json` holds the counts. JUnit counts and the scripted `imaptest` results are exact in both directions,
failing tests listed by name, each with a reason: a commit that makes a test pass or fail moves its floor in the
same commit (`marin/lane.sh --update ...`, then review the diff). Stress violations depend on how the clients
interleave (eleven runs gave 220 to 329), so each kind has a ceiling instead, about 1.5 times the highest
seen, set by hand and dropped to 0 as each fix lands.

Tools are pinned in `lane/toolchain.env` and fetched or built once into `$LANE_CACHE` (`~/.cache/james-lane`):
Temurin 21, Maven, and `imaptest` with the Dovecot libraries it needs, built at a commit because Dovecot
publishes only a moving `latest` binary. PostgreSQL 18 comes from the system (`PG_BINDIR`). Docker is not needed.
