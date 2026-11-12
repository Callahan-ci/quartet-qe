package io.quartetqe.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Android through adb alone. Nothing is installed on the device. */
final class AndroidBridge implements NativeSurface.Bridge {
    interface Shell {
        String run(List<String> command) throws Exception;
    }

    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");

    private static final long SETTLE_MS = 30_000;
    private static final String BROWSER = "com.android.chrome";
    private static final Pattern WEB_TEXT = Pattern.compile(" (?:text|content-desc)=\"[^\"]+\"");

    private final Shell shell;
    private final long settleMs;
    private final List<String> adb;

    AndroidBridge(String serial) {
        this(serial, AndroidBridge::execute, SETTLE_MS);
    }

    AndroidBridge(String serial, Shell shell) {
        this(serial, shell, 0);
    }

    AndroidBridge(String serial, Shell shell, long settleMs) {
        this.shell = shell;
        this.settleMs = settleMs;
        this.adb = new ArrayList<>(List.of(adbPath()));
        if (serial != null && !serial.isBlank()) adb.addAll(List.of("-s", serial));
    }

    /** True only when adb is installed and a device or emulator is connected and ready. */
    static boolean available() {
        try {
            return execute(List.of(adbPath(), "devices")).lines().anyMatch(line -> line.endsWith("\tdevice"));
        } catch (IOException | IllegalStateException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String adbPath() {
        return locateAdb(System.getenv(), Path.of(System.getProperty("user.home")), Files::isExecutable);
    }

    /** Where an Android SDK usually lives, so adb is found without anyone setting ANDROID_HOME. */
    static List<Path> sdkCandidates(Map<String, String> env, Path home) {
        List<Path> candidates = new ArrayList<>();
        for (String name : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            if (env.get(name) != null && !env.get(name).isBlank()) candidates.add(Path.of(env.get(name)));
        }
        candidates.add(home.resolve("Library/Android/sdk"));
        candidates.add(home.resolve("Android/Sdk"));
        if (env.get("LOCALAPPDATA") != null) candidates.add(Path.of(env.get("LOCALAPPDATA"), "Android", "Sdk"));
        return candidates;
    }

    static String locateAdb(Map<String, String> env, Path home, java.util.function.Predicate<Path> executable) {
        for (Path sdk : sdkCandidates(env, home)) {
            for (String name : List.of("adb", "adb.exe")) {
                if (executable.test(sdk.resolve("platform-tools").resolve(name))) return sdk.resolve("platform-tools").resolve(name).toString();
            }
        }
        return "adb";
    }

    /** What to tell a person when an adb call failed because of the machine, or null when it is something else. */
    static String explain(String output) {
        String text = output == null ? "" : output.toLowerCase();
        if (text.contains("no devices/emulators found") || text.contains("no devices found")) {
            return "No Android device is connected. Start an emulator (list yours with: emulator -list-avds, start one with: emulator -avd NAME) "
                    + "or plug in a phone with USB debugging on, then check that `adb devices` lists it.";
        }
        if (text.contains("device offline")) return "The Android device is offline. Wait for it to finish starting, or restart it, then check `adb devices`.";
        if (text.contains("unauthorized")) return "The Android device has not allowed this computer. Accept the USB debugging prompt on the device, then check `adb devices`.";
        if (text.contains("more than one device")) return "More than one Android device is connected. Name the one to use with serial: in the device's settings in the environment file.";
        if (text.contains("device '") && text.contains("not found")) return "The Android device named in serial: is not connected. Check `adb devices` for its exact name.";
        return null;
    }

    static String adbMissingMessage() {
        return "adb was not found. Install the Android SDK platform tools (Android Studio includes them) and either set ANDROID_HOME to the SDK folder "
                + "or put adb on your PATH. Looked in: " + sdkCandidates(System.getenv(), Path.of(System.getProperty("user.home"))) + " and the PATH.";
    }

    private static String execute(List<String> command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new EnvironmentProblem(adbMissingMessage());
        }
        byte[] output = process.getInputStream().readAllBytes();
        String text = new String(output, StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            String explained = explain(text);
            if (explained != null) throw new EnvironmentProblem(explained);
            // adb prints its own arguments back on a failure, which hides the reason. Keep only the lines that explain it.
            String reason = text.lines().filter(line -> line.startsWith("error") || line.startsWith("adb:") || line.toLowerCase().contains("exception")).limit(3)
                    .reduce((a, b) -> a + "\n" + b).orElse(text.strip().lines().limit(3).reduce((a, b) -> a + "\n" + b).orElse(""));
            throw new IllegalStateException("adb failed: " + reason);
        }
        return text;
    }

    private String run(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(adb);
        command.addAll(List.of(arguments));
        return shell.run(command);
    }

    @Override
    public void openUrl(String url) throws Exception {
        // A cold start means no test can read the page left behind by the previous one.
        run("shell", "am", "force-stop", BROWSER);
        run("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-p", BROWSER, "-d", url);
        awaitPageContent();
    }

    /** Waits until the web page has content and that content has stopped changing. */
    private void awaitPageContent() throws Exception {
        long deadline = System.currentTimeMillis() + settleMs;
        int previous = -1;
        while (System.currentTimeMillis() < deadline) {
            int current = webContentCount(run("exec-out", "uiautomator", "dump", "/dev/tty"));
            if (current > 0 && current == previous) return;
            previous = current;
            Thread.sleep(500);
        }
    }

    static int webContentCount(String xml) {
        int web = xml.indexOf("android.webkit.WebView");
        if (web < 0) return 0;
        return (int) WEB_TEXT.matcher(xml.substring(web)).results().count();
    }

    private static final Pattern COMPONENT = Pattern.compile("(?m)^([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\\s*$");

    /**
     * Starts the app the way a user does: from cold, so no test inherits the screen the last one left, using the
     * launcher component Android itself reports. Some apps start through an alias that "monkey" silently ignores.
     */
    @Override
    public void launchApp(String appId) throws Exception {
        run("shell", "am", "force-stop", appId);
        String component = launcherComponent(run("shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", appId));
        if (component == null) {
            run("shell", "monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1");
            return;
        }
        String started = run("shell", "am", "start", "-W", "-n", component);
        if (started.contains("Error:") || started.contains("Exception")) {
            throw new IllegalStateException("Android could not start " + appId + " (" + component + "). Is the app installed on this device? "
                    + "Install it with adb install, then try again.\n" + started.strip());
        }
    }

    /** The "package/activity" line from resolve-activity, or null when the app has no launcher activity. */
    static String launcherComponent(String output) {
        java.util.regex.Matcher matcher = COMPONENT.matcher(output == null ? "" : output);
        String found = null;
        while (matcher.find()) found = matcher.group(1);
        return found;
    }

    @Override
    public List<Surface.Element> elements() throws Exception {
        String output = run("exec-out", "uiautomator", "dump", "/dev/tty");
        int start = output.indexOf("<?xml");
        int end = output.lastIndexOf("</hierarchy>");
        if (start < 0 || end < 0) throw new IllegalStateException("The device did not return a screen hierarchy.");
        return parse(output.substring(start, end + "</hierarchy>".length()));
    }

    static List<Surface.Element> parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<Surface.Element> out = new ArrayList<>();
        walk(document.getDocumentElement(), out, false);
        return out;
    }

    private static void walk(Element node, List<Surface.Element> out, boolean parentIsWeb) {
        boolean web = parentIsWeb || node.getAttribute("class").endsWith("WebView");
        if (node.getTagName().equals("node")) {
            String description = node.getAttribute("content-desc");
            String text = node.getAttribute("text");
            String name = description.isEmpty() ? text : description;
            Matcher bounds = BOUNDS.matcher(node.getAttribute("bounds"));
            String role = role(node.getAttribute("class"), node.getAttribute("clickable"), web);
            if ((!name.isEmpty() || role.equals("textbox")) && bounds.matches()) {
                int left = Integer.parseInt(bounds.group(1));
                int top = Integer.parseInt(bounds.group(2));
                String value = description.isEmpty() || text.isEmpty() ? "" : text;
                boolean secret = "true".equals(node.getAttribute("password"));
                out.add(new Surface.Element(role, name, secret ? "" : value, 0, left, top,
                        Integer.parseInt(bounds.group(3)) - left, Integer.parseInt(bounds.group(4)) - top));
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element next) walk(next, out, web);
        }
    }

    private static String role(String className, String clickable, boolean web) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return switch (simple) {
            case "Button", "ImageButton", "MaterialButton" -> "button";
            case "EditText", "AutoCompleteTextView" -> "textbox";
            case "CheckBox" -> "checkbox";
            case "Switch", "SwitchCompat" -> "switch";
            case "RadioButton" -> "radio";
            case "ImageView" -> "true".equals(clickable) ? "button" : "img";
            // Chrome exposes web links as generic clickable views.
            case "View" -> "true".equals(clickable) ? (web ? "link" : "button") : "text";
            default -> "true".equals(clickable) ? "button" : "text";
        };
    }

    @Override
    public void tap(int x, int y) throws Exception {
        run("shell", "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    @Override
    public void typeText(String text) throws Exception {
        // adb's input command reads spaces as %s, and its argument passes through the device shell.
        String escaped = text.replace("\\", "\\\\").replace(" ", "%s").replaceAll("([\"'`$&|;<>()*?#~!])", "\\\\$1");
        run("shell", "input", "text", escaped);
    }

    @Override
    public void swipe(int fromX, int fromY, int toX, int toY) throws Exception {
        run("shell", "input", "swipe", String.valueOf(fromX), String.valueOf(fromY), String.valueOf(toX), String.valueOf(toY), "300");
    }

    @Override
    public void back() throws Exception {
        run("shell", "input", "keyevent", "4");
    }
}
