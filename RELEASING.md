# Releasing Quartet QE

A release has two parts: the program (a GitHub release built from a tag) and the Claude plugin (the `plugin/` folder, which fetches that exact release).

## 1. Publish a release

1. Make sure `main` is green.
2. Push a tag: `git tag v0.2.0 && git push origin v0.2.0`.
3. The **Release** workflow tests, builds the zip and one extension bundle per platform, validates the manifest, runs the first-time-user check, adds a software bill of materials and checksums, signs the bundles if a certificate is configured, and attaches everything to a GitHub release. Read its log: it says whether the bundles were signed.
4. On the release page, check the files and `SHA256SUMS` are there.

## 2. Pin the plugin to it

The plugin only downloads the file whose SHA-256 it has written down, so it must be updated after each release:

```bash
scripts/update-plugin-lock.sh 0.2.0      # reads that release's SHA256SUMS from GitHub
git diff plugin                          # the version and four checksums change
```

Commit that to `main`. The version in `plugin/.claude-plugin/plugin.json` is raised for you; the directory needs it raised with every release.

## 3. List it in Anthropic's directory (first time)

Local servers are submitted as a plugin, not as an extension. You need a paid Claude plan, the repository public, and your GitHub account connected on claude.ai.

1. Check locally: `claude plugin validate ./plugin` and `claude plugin validate .`.
2. Open https://claude.ai/directory/manage, choose **Submit new**, then **Plugin bundle**.
3. Repository: `Callahan-ci/quartet-qe`. Plugin path: `plugin`. Leave the branch empty to follow `main`.
4. Select **Validate**, fix anything marked **Blocks**, and validate again.
5. Answer the data handling questions from `plugin/README.md` and `PRIVACY.md`, tick the acknowledgements, and submit.

Expect Anthropic's reviewer to read the first version: the launcher downloads a release, which the directory always checks by hand. After it is live, each merge to `main` that changes the plugin is scanned and published according to the portal setting.

## Without the directory

Anyone can use the plugin today from this repository as a marketplace:

```
/plugin marketplace add Callahan-ci/quartet-qe
/plugin install quartet-qe@quartet-qe
```
