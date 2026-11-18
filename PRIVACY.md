# Privacy

Quartet QE runs on your computer. It has no account, no server of its own and no usage tracking: it does not send anything to its author or to anyone else on its own account.

## What leaves your computer, and only when you set it up

- **The AI you are chatting with.** When Quartet is used through a chat tool, what its tools return (story text, context files, test code, results) goes to that chat tool's AI under that tool's own terms. Quartet does not control or see that.
- **An AI provider, if you add a key.** For unattended runs you can give Quartet an API key. It then sends prompts to that provider (Anthropic, OpenAI or Google) under their terms.
- **Your own systems.** Jira, Azure DevOps, GitHub, GitLab and Jenkins are called only if you enter their addresses and tokens, and only at the addresses you gave. The systems you test (your APIs, databases, brokers, sites and devices) are contacted only if the environment file lists them.
- **Sites and files you point it at.** Fetching an API description from a link follows redirects, refuses private and local addresses unless you allow them, and sends nothing but the request.

## What is stored, and where

- Tests, recordings, reports and settings are saved in your tests repo on your disk. Recordings and reports hold what was typed or shown during a run. Secrets Quartet knows about are removed from them; a password typed from a story is not known to it, so use test accounts.
- Tokens entered in the extension's settings are kept by the app that installed it. Tokens saved with `./quartet-qe secret NAME` go in a `.env` file in your tests repo, readable only by you and excluded from git.
- Quartet keeps a small workspace of its own at `~/.quartet`, and creates new tests repos in `~/quartet-tests` unless you choose another folder.

## Your control

Delete the repo folder, `~/.quartet` and `~/quartet-tests` to remove everything Quartet stored. Revoke the tokens you gave it in the systems that issued them.

Security questions and reports: see [SECURITY.md](SECURITY.md).
