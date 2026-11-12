# __NAME__

End-to-end tests written by [Quartet QE](__QUARTET_URL__) and reviewed by people.

## How it works

1. **Give it context.** Describe your systems in `context/`.
2. **Give it a story.** A file in `stories/`, or a Jira, Azure DevOps, GitHub or GitLab reference.
3. **It writes the tests.** `./quartet-qe run --story stories/YOUR-STORY.md`
4. **You review.** Open `reports/<story>/review.md`. It lists what needs your attention first.

To open a pull request with the review as its description:

```bash
./quartet-qe publish --story jira:SHOP-142 --code github
```

## Setup

Needs JDK 21. Copy `.env.example` to `.env`, add your AI key, then:

```bash
./quartet-qe doctor
```

## Jenkins

Add your Jenkins address, user and API token to `connectors.yaml`, then run:

```bash
./quartet-qe setup-jenkins
```

It creates a multibranch pipeline job for this repo, so Jenkins builds every branch and pull request using the `Jenkinsfile`.

## Running the tests

```bash
./gradlew test
```

Tests replay from `recordings/` and never call the AI. That is the default. Only `./quartet-qe run` and `repair` record steps with the AI, and a missing recording fails with a message saying so. Set `QUARTET_MODE=auto` only if you want a test run to be allowed to call the AI.

## What lives here

| Path | Purpose |
| --- | --- |
| `context/` | What the agent knows: principles, services, contracts, telemetry, environments |
| `stories/` | Stories kept as files |
| `src/test/java/generated/` | The tests, one package per story |
| `recordings/` | Verified steps that replay without AI. Commit these |
| `reports/` | One review page per story |
| `Jenkinsfile` | Found automatically by a Jenkins multibranch pipeline |
| `.quartet/` | The framework, pinned as a git submodule |
