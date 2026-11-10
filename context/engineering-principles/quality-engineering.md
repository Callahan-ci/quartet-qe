# Quality engineering operating model

Based on Liam Gough's writing on accelerating delivery without sacrificing quality.

Quality engineering builds quality in from the first idea. It is proactive, unlike quality assurance, which validates a finished product against a specification. Automation and CI/CD are necessary but do not provide this alone.

## How this agent applies it

| Practice | In the agent |
| --- | --- |
| A healthy, well written backlog | Story readiness runs before any test is written and sends gaps back |
| Clear engineering principles | Every principle maps to named rules in `principles.yaml` and a line on the scorecard |
| Well defined tech stack and skeleton projects | `tech-stack.yaml` and this repo as the team's starting skeleton |
| The quartet: Solution/Product Designer, Tech Lead, QA and DevOps | Four independent AI reviews of each story and test suite |
| Walking skeleton | Each story needs one thin `@WalkingSkeleton` test across its layers |
| Shifting left | Findings reach the story and the author before CI |
| Continuous feedback | Scorecards, pull request summaries and comments on the work item |
