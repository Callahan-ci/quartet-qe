# Getting started with Quartet QE

This guide takes you from a fresh clone to tests for your own product, with a pipeline that runs them. It takes about 15 minutes to see the demo, and an afternoon to point it at your own system.

## What you need

| Need | For | Check |
| --- | --- | --- |
| JDK 21 | Everything | `java -version` |
| git | Everything | `git --version` |
| An AI | Writing new tests: the AI in your chat tool (no key), or an API key. Not needed to run the demo, or to replay tests you already have | See [step 4](#4-give-it-an-ai) |
| Android SDK platform tools | Optional. Android apps and on-device browsers | `adb version`, and `ANDROID_HOME` set |
| Xcode | Optional. iOS apps | See `native/ios/README.md` |
| Jenkins with the Pipeline: Multibranch, Git and Folders plugins | Optional. Pipelines | Your Jenkins admin |

No Docker is needed. The demo starts its own Kafka, PostgreSQL and MongoDB.

## 1. Run the demo

```bash
git clone https://github.com/goughliam1813/quartet-qe.git
cd quartet-qe
./demo.sh
```

The first run downloads dependencies and a browser, so allow a few minutes. The demo starts a sample shop called ShopFlow, then walks through four things:

1. **A vague story gets questions.** `SHOP-200` is too loose to test, so the agent lists what it needs answered instead of guessing.
2. **A good story becomes tests and a review.** `SHOP-142` has seven acceptance criteria. The agent reads the context, writes the tests, checks its own code, runs them against real Kafka, PostgreSQL, MongoDB and Chromium, and plants bugs to prove the tests catch them.
3. **The second run uses no AI.** The tests replay from the recordings made in step 2.
4. **A brittle test is repaired without being weakened.** The repair keeps every assertion.

The demo uses a scripted stand-in for the AI, so it needs no key and shows the framework working rather than AI judgement.

To see the chat path as well, run `./demo-chat.sh`. It starts the real MCP server and answers each model step through it, the way a chat tool would.

Open the review page it produced:

```bash
open reports/SHOP-142/review.md      # macOS. On Linux use xdg-open
```

It lists anything that needs your attention first, then what passed and the evidence for each acceptance criterion.

## 2. Understand what you are looking at

Four folders matter:

| Folder | What it holds |
| --- | --- |
| `context/` | What the agent knows: your principles, services, contracts, telemetry and environments |
| `stories/` | The stories to test, as markdown files |
| `e2e-tests/src/test/java/generated/` | The tests the agent wrote, one package per story |
| `e2e-tests/recordings/` | The verified steps that replay with no AI |

A test states intent and decides with plain Java assertions:

```java
ActResult order = agent.act("Place an order for 2 units of SKU-1001 for customer C-42",
        why("A customer checking out is the start of the reservation journey"),
        on("orders-api"),
        returns("orderId", "status"));

assertThat(order.get("status")).isEqualTo("RESERVED");
```

## 3. Create a tests repo for your product

Tests live in their own repo, one per product or team. Do not add your product's tests to this repo.

```bash
./new-tests-repo.sh ../my-product-tests
cd ../my-product-tests
```

You get a ready project with the framework pinned as a submodule in `.quartet/`, a `Jenkinsfile`, a `context/` folder, and a script that saves your AI key.

Commit it, and push it to your own git host when you are ready.

## 4. Give it an AI

Choose one. You can switch at any time.

### Option A: the AI you are already chatting with (no key)

Open the tests repo in a chat tool that supports MCP, such as Claude Code, Claude Desktop or Cursor. The repo's `.mcp.json` already points at `./quartet-qe mcp`. Approve the server when asked, then say:

> Write tests for stories/MY-STORY.md using Quartet QE.

The system your tests talk to must be running first. Quartet checks that before it runs the tests, and the AI will tell you what to start if something is down. (For the demo shop, run `./demo-shop.sh` in a terminal and leave it open.)

The AI follows `AGENTS.md`: it starts the story, reads your context, writes and validates the tests, hands them over, and answers the review and recording steps Quartet asks for. You get the review page at the end. The first start builds the tool, so allow a minute.

If your chat tool does not read `.mcp.json`, add a server named `quartet-qe` that runs `./quartet-qe mcp` from the repo root.

Do [step 5](#5-tell-it-about-your-system) first, because the AI reads your context. Then skip steps 6 and 7, because the chat does them, and continue at [step 8](#8-review-then-replay-with-no-ai).

### Option B: an API key

```bash
./set-ai-key.sh
```

It asks which provider you use and saves the key to `.env` with the input hidden. `.env` is never committed. You can also copy `.env.example` to `.env` and edit it by hand:

```bash
AI_PROVIDER=anthropic        # anthropic | openai | azure-openai | gemini | openai-compatible
AI_API_KEY=your-key
AI_MODEL=                    # needed for every provider except anthropic
AI_BASE_URL=                 # needed for azure-openai and openai-compatible
```

Then check it:

```bash
./quartet-qe doctor
```

`doctor` checks the AI connection, your context and your environment, and says what to fix. The first run of `./quartet-qe` builds the tool, which takes a minute. Use Option B for unattended runs, such as a pipeline that writes tests for a story.

## 5. Tell it about your system

Edit the files in `context/`. You can start small and add more as you go.

**Environment.** Copy and edit `context/environments/default.yaml`. List each thing the tests can reach, and which hosts are allowed:

```yaml
name: default
guard:
  production: false
  allowHosts: [localhost, staging.example.com]   # tests can only reach these hosts
targets:
  orders-api:
    type: api
    description: Orders REST API.
    baseUrl: "${ORDERS_API_URL:-http://localhost:8080}"
    context: [contracts/openapi/orders-service.yaml]
  orders-db:
    type: postgres
    description: Orders database, read-only for tests.
    url: "${ORDERS_DB_URL:-jdbc:postgresql://localhost:5432/orders}"
    user: "${ORDERS_DB_USER:-}"
    password: "${ORDERS_DB_PASSWORD:-}"
```

Target types are `api`, `screen` (web and mobile), `kafka`, `postgres` and `mongo`. Databases are read-only unless a target explicitly allows writes. Secrets come from environment variables, never from the file.

**Contracts.** Put OpenAPI specs, Pact files and event schemas under `context/contracts/`, and point targets at them with `context:`. The agent reads them before writing a test.

**Services and telemetry.** Describe each service in `context/services/` and its metrics and objectives in `context/telemetry/`. The `ShopFlow` examples in this repo's `context/` show the shape.

**Principles.** `context/engineering-principles/principles.yaml` holds the rules the tests are checked against. Set each to `block`, `warn` or `off` to suit your team.

**Source repos.** In `context/repos/repos.yaml`, list repositories the agent may read, with `provider: local`, `github`, `gitlab` or `ado`.

## 6. Write a story

A story is a markdown file in `stories/`:

```markdown
# SHOP-142: Checkout reserves stock and confirms the order

As a customer I want my items reserved when I check out, so that I am never sold something that is out of stock.

## Acceptance criteria

- AC1: A valid order for in-stock items returns 201 with status RESERVED and is persisted in the orders database.
- AC2: An order exceeding available stock is rejected with 409 INSUFFICIENT_STOCK and stock does not change.
```

Write criteria a person could check: a number, a status, a screen, an event. Vague criteria get questions back, not tests. Check a story before you spend any AI on it:

```bash
./quartet-qe check-story --story stories/SHOP-142.md
```

You can also read stories straight from your tracker:

```bash
./quartet-qe check-story --story jira:SHOP-142     # also ado:123, github:45, gitlab:7
```

For that, add the address and token to `connectors.yaml`. Tokens come from environment variables, for example `JIRA_URL`, `JIRA_EMAIL` and `JIRA_TOKEN`.

## 7. Write and run the tests

```bash
./quartet-qe run --story stories/SHOP-142.md
```

This is the one step that uses the AI. It:

1. reads your context and the story,
2. writes the tests under `src/test/java/generated/`,
3. compiles them and checks them against your principles, fixing what blocks,
4. asks an independent second opinion through four lenses: Product Designer, Tech Lead, QA and DevOps,
5. runs them against your environment and records each verified step,
6. writes `reports/SHOP-142/review.md`.

Useful options:

| Option | Effect |
| --- | --- |
| `--no-run` | Write the tests without running them |
| `--prove` | Plant bugs in a test build and check the tests catch them. Slower, and worth doing before you trust a suite |
| `--environment <file>` | Use another environment file |

## 8. Review, then replay with no AI

Open `reports/<story>/review.md`. Anything that needs your attention is at the top. Read the generated tests like any other code, then commit the tests and the `recordings/` folder together.

Now run them with no AI:

```bash
./gradlew test
```

This is the default. Tests replay from `recordings/`, so they are fast, free and repeatable. A missing recording fails with a message saying so. If you do want a test run to be allowed to ask the AI when a step is missing or stale, set `QUARTET_MODE=auto`.

A summary line shows what happened:

```text
[quartet] Recordings: 8 replayed, 0 handed off, 0 missed | AI: 0 model calls
```

For one page about the last run, including per-test timing and failure screenshots:

```bash
./quartet-qe report --results build/test-results/test
```

## 9. Open a pull request

```bash
./quartet-qe publish --story jira:SHOP-142 --code github
```

This runs the story, commits the tests to a new branch, and opens a pull request with the review as its description. A person approves the pull request. Nothing is merged by the agent.

`--code` takes `github`, `gitlab` or `ado`. Add `--ci jenkins` (or `github`, `gitlab`, `ado`) to start your pipeline and follow it to the end.

## 10. Set up Jenkins

Put your Jenkins address, user and API token in `connectors.yaml`:

```yaml
jenkins:
  baseUrl: "https://jenkins.your-company.com"
  job: "team/app/e2e"            # folders are created for you
  user: "${JENKINS_USER}"
  token: "${JENKINS_TOKEN}"
```

Then:

```bash
./quartet-qe setup-jenkins
```

It writes a `Jenkinsfile` if the repo has none, creates the folders, and creates a multibranch pipeline job pointed at your repo. Commit and push the `Jenkinsfile`. Jenkins then builds every branch and pull request, and each build replays the recorded steps with no AI, so a pipeline run needs no AI key.

The repository address comes from your `origin` remote. For a private repo, add a credential in Jenkins and set `credentialsId` under `jenkins:`. An address with a password or token in it is refused.

To let a build write tests for a story, add a Jenkins secret named `quartet-ai-key` and run the build with the `STORY` parameter.

## 11. Test phones and native apps

**Phone browsers** need nothing extra. Add devices to a `screen` target:

```yaml
  shop-web:
    type: screen
    baseUrl: "https://staging.example.com"
    devices:
      desktop: {kind: web, engine: chromium}
      iphone: {kind: web, engine: webkit, preset: iphone}
      android-phone: {kind: web, engine: chromium, preset: android}
      android-device: {kind: android}          # an emulator or phone listed by adb; add serial: to pick one
```

Mark a test with `@OnEverySurface("shop-web")` to run it on every device.

**Android apps and on-device browsers** use `adb` directly, with nothing installed on the device. Start an emulator or plug in a phone, make sure `adb devices` lists it, set `ANDROID_HOME`, and add the `android-device` entry shown above. To see what the agent sees on a screen:

```bash
./quartet-qe observe --target shop-web --device android-device
```

**iOS apps** need the XCTest runner in `native/ios`. It has not yet been run, so treat it as unproven. See `native/ios/README.md`.

A hosted Chromium (browserless, Kernel, a Docker sidecar) can replace the local browser by setting `cdpUrl` on a web device. It must pass the same host guard.

## When something goes wrong

| What you see | What to do |
| --- | --- |
| `No AI configured` | Use your chat AI (Option A in step 4), run `./set-ai-key.sh`, or create `.env` from `.env.example` |
| The chat AI cannot see the Quartet tools | Check the server is approved in your chat tool, and that `./quartet-qe mcp` starts from the repo root. Anything printed to the terminal other than the protocol means a setup problem. Run `./quartet-qe doctor` |
| `The chat AI did not answer step` | The chat stopped answering. Ask it to call `quartet_next_step` and carry on |
| `Stopped` followed by a message | Expected problems are reported without a stack trace. The message says what to change |
| The AI service is busy or over quota | The tool retries patiently and shows progress. If it keeps failing, check your quota or switch provider in `.env` |
| A test fails with `no recording` | Run `./quartet-qe run` once, or use `QUARTET_MODE=auto` |
| A test fails with `Recording is stale` | The replay rules changed since it was recorded. Run `./quartet-qe run` to record again |
| A step fails after a UI change | Run `./quartet-qe repair --file <Test.java> --selector '<package>.*'`. It fixes the test without weakening it. If the product is wrong, you get a bug report instead |
| `Jenkins did not find the branch` | Push the branch, and check Jenkins can read the repo |
| A host is blocked | Add it to `allowHosts` in your environment file, if you really mean to test it |

Run `./quartet-qe doctor` any time to check your setup.

## Next steps

- Add the rest of your systems to `context/`, so tests can cross API, event, database and screen in one story.
- Run `./quartet-qe run --prove` on an important story to see the tests catch planted bugs.
- Wire `publish` into your team's flow so every story arrives as a pull request with its review attached.
- Read the [README](README.md) for how the checks work and the current limits.
