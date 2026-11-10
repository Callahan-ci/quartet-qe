package io.quartetqe.runtime;

/** Shows a person exactly what the agent perceives on a device. */
public final class ScreenInspector {
    private ScreenInspector() {}

    public static String describe(Environment environment, String target, String device, String path) throws Exception {
        try (ScreenDriver driver = new ScreenDriver(environment, target, device, true)) {
            driver.surface().open(path);
            Surface.Screen screen = driver.surface().observe();
            StringBuilder out = new StringBuilder("Layout: ").append(driver.surface().layout()).append("\n\nControls:\n");
            screen.elements().stream().filter(e -> !e.name().isEmpty()).forEach(e -> out.append("  ").append(e.describe()).append('\n'));
            return out.append("\nText:\n").append(screen.text()).toString();
        }
    }
}
