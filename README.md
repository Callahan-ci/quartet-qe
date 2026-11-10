# Quartet QE

**Quality engineering agents that prove their work.**

1. **Give it context.** What your systems are and how your team builds.
2. **Give it a story.** From a file, Jira, Azure DevOps, GitHub or GitLab.
3. **It writes the tests.** For APIs, Kafka, databases, web, mobile web and native apps.
4. **You review.** One page, with anything that needs your attention at the top.

```bash
./quartet-qe run --story stories/SHOP-142.md
```

```text
Context  26 files, 12 engineering principles
Story    SHOP-142: Checkout reserves stock and confirms the order (7 acceptance criteria)
Tests    6 written: 6 passed, 0 failed, 0 skipped
Review   reports/SHOP-142/review.md (ready to merge)
```

## What a test looks like

```java
ActResult order = agent.act("Place an order for 2 units of SKU-1001 for customer C-42",
        why("A customer checking out is the start of the reservation journey"),
        on("orders-api"),
        returns("orderId", "status"));

assertThat(order.get("status")).isEqualTo("RESERVED");
```

The agent decides how to act. Plain Java assertions decide whether it worked, so the AI never marks its own homework. The same test runs on every screen with `@OnEverySurface("shop")`: desktop browsers, phone browsers, and native Android and iOS, with no selectors anywhere.

## Start a tests repo

Tests live in their own repo, one per product or team, not in this one.

```bash
./new-tests-repo.sh ../my-product-tests
```

That gives you a ready project with the framework pinned as a submodule, a `Jenkinsfile`, and a `context/` folder to fill in. A Jenkins multibranch pipeline pointed at the repo finds the `Jenkinsfile` by itself, so every branch and pull request gets a job without anyone creating one.

Add your AI key to `.env` in that repo. Supported: `anthropic`, `openai`, `azure-openai`, `gemini` and `openai-compatible` (Ollama, vLLM, LiteLLM, internal gateways).

## Why you can trust it

Every check below runs inside `run`. You only hear about one when it finds something.

- **No invented values.** Anything the AI reports must exist in a real tool result.
- **It reads before it writes.** It cannot finish without reading your principles, contracts and telemetry.
- **It checks its own code.** Tests are compiled and held to your engineering principles before you see them.
- **A second opinion.** An independent review looks through four lenses: Product Designer, Tech Lead, QA and DevOps.
- **Only verified steps are recorded**, and they replay with no AI. A renamed button is handed back to the AI, never guessed.
- **Repairs cannot weaken a test.** No deleted assertions, no disabled tests, no changed expectations without evidence. A product bug is reported, not patched around.
- **Proof it catches bugs.** With `--prove`, bugs are planted in a test build and the tests must catch them.
- **Guard rails.** Only allow-listed hosts, read-only databases by default, and secrets removed from prompts and reports.

## See it working with no key

Needs JDK 21. No Docker.

```bash
./demo.sh
```

The demo runs against real Kafka, PostgreSQL, MongoDB and Chromium. It uses a scripted stand-in for the AI, so it shows the framework working rather than AI judgement.

## Screens

| Surface | How it connects | Needs |
| --- | --- | --- |
| Desktop and phone browsers | Playwright, using each browser's accessibility tree | Nothing extra |
| Android apps and on-device browsers | `adb` directly, nothing installed on the device | Android SDK platform tools, with `ANDROID_HOME` set |
| iOS apps and Safari | The runner in `native/ios` | Xcode. Not yet run, see `native/ios/README.md` |

Android's accessibility layer does not expose link addresses or page width, so tests of those use `kinds = "web"`.

## Layout

| Folder | Purpose |
| --- | --- |
| `quartet-core` | AI providers and the agent loop |
| `quartet-runtime` | `agent.act`, `agent.verify`, screens, and drivers for API, Kafka, PostgreSQL and MongoDB |
| `quartet-generator` | The command line tool, connectors, checks and repair |
| `skeleton` | The starting point for a tests repo |
| `native/ios` | The iOS runner |
| `context`, `stories`, `demo-shopflow`, `e2e-tests` | The ShopFlow demo |

The quality model is based on Liam Gough's writing on quality engineering.
