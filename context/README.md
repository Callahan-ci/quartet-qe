# Context repo

Everything the agent knows about your systems. Better context produces better tests, so treat this as a product with an owner.

| Folder | What goes here | Used for |
| --- | --- | --- |
| `engineering-principles/` | Principles as enforceable rules, standards, definition of done | Prompts, static rules, scorecards |
| `services/` | One file per service: owner, dependencies, data, events | Understanding the journey |
| `contracts/` | OpenAPI, Pact files, event schemas | Stopping the AI inventing endpoints and fields |
| `telemetry/` | Metrics, spans, logs, objectives, real traffic | Observability and latency tests |
| `repos/` | Git references: local, GitHub, GitLab or Azure DevOps | Reading service code |
| `environments/` | Named targets and the allowed-host guard | Where tests may run |
| `test-data/` | Safe synthetic data | Avoiding real personal data |
| `playbooks/` | How your team tests each layer | Team conventions |
| `quality/` | Planted faults | Measuring whether tests catch bugs |
| `domain/` | Glossary | Shared language |

To adopt it, replace the ShopFlow examples with your own services, then run `quartet-qe doctor`.
