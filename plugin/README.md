# Quartet QE for Claude

Write end-to-end tests from a user story, in plain English. The AI you are chatting with writes the tests, checks its own work, and proves they catch bugs. After the first recording the tests replay with no AI, in your own pipeline, and a person approves everything before it merges. No AI key is needed.

It tests REST APIs, Kafka, Postgres and MongoDB, web pages in Chromium, Firefox and WebKit, phone browsers, and native Android apps, and gives you one review page with the verdict, every acceptance criterion, the evidence and screenshots.

## Install

In Claude Code:

```
/plugin marketplace add Callahan-ci/quartet-qe
/plugin install quartet-qe@quartet-qe
```

Or, on Claude Code 2.1.275 or later: `/plugin install quartet-qe --marketplace Callahan-ci/quartet-qe`.

## Use it

Ask Claude, for example:

- "Test my site https://www.example.com using Quartet QE."
- "Write tests for this story using Quartet QE: ..."
- "Help me set up Quartet QE for my product."

The first time, it asks what your product is called and where the tests should live (a new repo is the recommended answer, created in `~/quartet-tests`). When it asks for a token, it names the variable and you add it in this plugin's settings or save it hidden in a terminal. Secrets never go through the chat.

## What this plugin runs, sends and fetches

- **It runs a local program.** Quartet QE is a Java program (Java 21 or newer is needed). The plugin's `scripts/start-quartet.sh` starts it. If you already have Quartet QE installed, set `QUARTET_HOME` to that folder and nothing is downloaded.
- **It fetches one file, once.** Otherwise the first start downloads this version's release from `https://github.com/Callahan-ci/quartet-qe/releases`, checks its SHA-256 against the value committed in this plugin (a mismatch stops before anything is unpacked), and unpacks it into `~/.quartet/releases/<version>`. The first web test also lets Playwright download its browsers.
- **It sends nothing to its author.** There is no account, no server of its own and no usage tracking. Test traffic goes only to the systems you list in your tests repo's environment file. Jira, Azure DevOps, GitHub, GitLab and Jenkins are contacted only if you give their addresses and tokens in the settings.
- **Test runs get a limited environment.** The tests are AI-written code, so they see the variables the environment file names and the basics, not your other secrets.
- **It writes to your tests repo and `~/.quartet`.** Recordings, reports (with screenshots) and settings are saved in the tests repo on your disk. See the [privacy page](https://github.com/Callahan-ci/quartet-qe/blob/main/PRIVACY.md) and the [security notes](https://github.com/Callahan-ci/quartet-qe/blob/main/SECURITY.md).

## Where it works

Claude Code, and Cowork sessions that run on your computer. Chat on the web ignores plugins that start a local program. Quartet QE supports macOS and Linux.

## More

Source, guide, the demo and the limits are at [github.com/Callahan-ci/quartet-qe](https://github.com/Callahan-ci/quartet-qe). Quartet QE is MIT licensed.
