# iOS runner

Apple only permits UI automation through XCTest, so iOS needs Xcode. This small runner is the only part that runs on the device.

**Status: not yet run.** The machine this was built on has no Xcode, so this file has been syntax-checked only.

1. In Xcode, create an app project with a UI Testing Bundle.
2. Add `AgentRunner.swift` to the UI testing target.
3. Start it on a simulator and leave it running:

```bash
xcodebuild test -scheme YourScheme -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:YourUITests/AgentRunner/testServe
```

4. Add a device to your target in the environment file:

```yaml
devices:
  ios-simulator: {kind: ios, enabled: true, runnerUrl: "http://127.0.0.1:8123"}
```
