# AI assistants: start here

This repo holds end-to-end tests, written with Quartet QE and reviewed by people. Use the `quartet-qe` MCP server set up in `.mcp.json`
(approve it when your tool asks). The server tells you how to work: call `quartet_onboarding` to set up, and `quartet_start` for a story.
To test a public website, pass `site` and `story_text` to `quartet_start`.

Never ask for, accept or repeat a token, password or key in the chat. Name the variable and have the user run `./quartet-qe secret NAME` in a terminal.
A person reviews and approves. Never merge or push for them.
