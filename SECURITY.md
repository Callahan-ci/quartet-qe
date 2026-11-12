# Security

## Reporting a problem

Please use GitHub's private vulnerability reporting on this repository (the Security tab) rather than a public issue. Include what you did, what happened, and what you expected.

## What Quartet QE has to defend against

Quartet QE lets an AI act on real systems and write code that runs on your machine. So there are three kinds of input it cannot fully trust:

- **The AI.** It can be steered by text it reads: a story, a web page under test, an API response, a file in a repo. Treat anything it does as if a stranger asked for it.
- **A repo.** Its `quartet.yaml`, environment files and tests are executed or followed.
- **Addresses and names that arrive through a chat**, such as a download link, a folder name or a story path.

The guard rails are built around that: the AI gets narrow tools, each tool checks its own inputs, and secrets never pass through the chat.

## Security review, 12 November 2026

This was a hands-on review. Each suspected hole was tried against the real system (real Postgres and MongoDB, a real Android emulator, the real scripts) before it was called a finding, and tried again after the fix. Every fix has a regression test (`SecurityGuardsTest` and `SecurityTest`).

| # | Finding | Impact | Fix |
| --- | --- | --- | --- |
| 1 | A read-only **Postgres** target was not read-only. `CREATE TABLE` and `INSERT` both succeeded. | An AI could change or destroy data in a database documented as read-only. | A read-only target now uses a read-only transaction that is rolled back after every query, plus a statement check with a clear message. The database itself refuses writes, including writes hidden in a `WITH` or `SELECT INTO`. |
| 2 | A read-only **MongoDB** target could be written through an aggregation `$out` or `$merge` stage. | Same: data changed on a read-only target. | `$out` and `$merge` are refused on read-only targets, wherever they are nested. Server-side code (`$function`, `$accumulator`, `$where`) is refused on every target. |
| 3 | **Text typed into an Android app ran as a command on the device.** A line break or tab in the text was passed to the device's shell. | A prompt-injected AI could run commands on an emulator or a real phone. | Everything sent to the device shell is quoted, control characters are refused, and app ids and addresses are validated. |
| 4 | **A crafted folder name ran a command** when creating a tests repo. The name was interpolated into a perl substitution. | Arbitrary command execution on the user's machine, through a path the AI supplied. | The name is validated before anything runs, values go to perl through the environment, and the path must be inside the user's home (or a temporary folder), not in a hidden folder. |
| 5 | **Downloads could reach private networks.** `contract_from_url` fetched any address, following redirects, and returned the answer to the AI. | Cloud metadata, internal services and localhost could be read through a link in a chat. | Private, local, link-local and carrier-grade addresses are refused unless the host is in `guard.allowHosts`. Every redirect is checked. |
| 6 | **Any file could be read through a story path.** `quartet_start` accepted a path anywhere on disk. | The first lines of an arbitrary file could reach the AI. | A story file must be inside the repo. |
| 7 | **Generated tests could do anything the user could.** Nothing stopped an AI-written test from running a program, reading a secret from the environment or opening a connection. | AI-written code runs with the user's privileges, and `.env` secrets are in its environment. | A blocking rule, `SEC-NO-SYSTEM-ACCESS`, refuses tests and steps that run programs, read files or variables, use reflection or open connections of their own. Repos with an older principles file get it as blocking automatically. |
| 8 | **Credentials could be sent over plain http** to another machine. | Tokens visible on the network. | Integrations must use https unless the host is this computer, or the team sets `allowHttp: true`. |
| 9 | **The AI could redirect credentials or widen the allow-list without the user noticing.** | A planted instruction could point Jira, Jenkins or GitHub at an attacker's host, or allow a new host. | Every such change now returns a plain statement of the address and the hosts, and the AI is told to put it to the user. |
| 10 | The chat's request and answer folder was readable by other users of the machine. | Local disclosure on a shared machine. | The folder is private to the user. |
| 11 | CI ran with default token permissions, and dependencies had no update checks. | Wider blast radius if a workflow were compromised; slow patching. | CI is read-only, and Dependabot is enabled for Gradle and GitHub Actions. |

## What you should still know

These are limits and trust assumptions, not fixed bugs.

- **AI-written tests are code.** The new rule closes the obvious doors, but a person should still read tests before merging, as the review page asks.
- **The host allow-list restricts what tests can reach. It does not restrain the setup AI**, which can edit it. Quartet says so every time, so look at changes to `context/environments/` and `connectors.yaml` before you commit them.
- **Treat a repo like a build script.** Running Quartet or `./gradlew test` in a repo you do not trust runs what it configures: the `tests.command` in `quartet.yaml`, JDBC URL options, and the tests themselves.
- **Read-only is enforced twice, but give the database account no write rights as well.** A function with side effects called from a `SELECT` is the sort of thing only the database can stop.
- **Recordings and reports contain what was typed or shown.** Secrets that Quartet knows about are removed. A password that an AI typed from a story is not known to it, so use test accounts and keep real credentials in `.env`.
- **A chat tool may keep what you type.** Quartet's tools refuse tokens and passwords, and secrets are saved with `./quartet-qe secret NAME`, but a secret pasted into a chat may already be stored by that tool. Rotate it.
- **Downloads check the address when they connect.** A hostile DNS server that changes its answer between the check and the request is not covered.
- **Symlinks inside a repo** can redirect a write to another place. Not guarded.
- **There has been no outside audit.** Dependency update checks are on, but there is no deeper scan of third-party code.

## Good practice

1. Point environments at test or staging systems, never production. Production is refused unless you say otherwise.
2. Give database targets an account with read rights only.
3. Keep tokens in `.env` (owner-only) and save them with `./quartet-qe secret NAME`.
4. Review the diff of environment files, connector settings and generated tests before merging.
5. Run Jenkins with the least access it needs, and pin the Quartet version (`quartetVersion` in `quartet.yaml`).
