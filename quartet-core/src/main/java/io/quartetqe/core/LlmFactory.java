package io.quartetqe.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Chooses the AI from environment variables, which is the only setup a team needs.
 *
 * Generic: AI_PROVIDER, AI_API_KEY, AI_MODEL, AI_BASE_URL.
 * Shortcuts: ANTHROPIC_API_KEY, OPENAI_API_KEY or GEMINI_API_KEY select that provider when AI_PROVIDER is unset.
 */
public final class LlmFactory {
    private LlmFactory() {}

    /** Reads settings from a .env file in the working directory, then the real environment, which wins. */
    public static Llm.Client fromEnv() {
        Map<String, String> merged = new HashMap<>();
        Path dotEnv = Path.of(".env");
        if (Files.isRegularFile(dotEnv)) {
            try {
                for (String line : Files.readAllLines(dotEnv)) {
                    int equals = line.indexOf('=');
                    if (equals > 0 && !line.strip().startsWith("#")) {
                        merged.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip().replaceAll("^[\"']|[\"']$", ""));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        merged.putAll(System.getenv());
        return fromEnv(merged);
    }

    public static Llm.Client fromEnv(Map<String, String> env) {
        String provider = blankToNull(env.get("AI_PROVIDER"));
        if (provider == null && blankToNull(env.get(HostClient.DIR_ENV)) != null) provider = "host";
        if (provider == null) {
            if (blankToNull(env.get("ANTHROPIC_API_KEY")) != null) provider = "anthropic";
            else if (blankToNull(env.get("OPENAI_API_KEY")) != null) provider = "openai";
            else if (blankToNull(env.get("GEMINI_API_KEY")) != null) provider = "gemini";
            else throw new Llm.LlmException("No AI configured. Put AI_PROVIDER and AI_API_KEY in a .env file, or see README.md.");
        }

        String model = blankToNull(env.get("AI_MODEL"));
        String baseUrl = blankToNull(env.get("AI_BASE_URL"));
        HttpJson http = new HttpJson(8, 2000);

        Llm.Client client = switch (provider.toLowerCase()) {
            case "anthropic", "claude" -> new AnthropicClient(baseUrl, key(env, "ANTHROPIC_API_KEY"), model, http);
            case "openai" -> new OpenAiClient(OpenAiClient.Flavor.OPENAI, baseUrl, key(env, "OPENAI_API_KEY"),
                    required(model, "AI_MODEL"), null, http);
            case "azure-openai", "azure" -> new OpenAiClient(OpenAiClient.Flavor.AZURE, required(baseUrl, "AI_BASE_URL"),
                    key(env, "AZURE_OPENAI_API_KEY"), required(model, "AI_MODEL (the Azure deployment name)"),
                    env.getOrDefault("AI_AZURE_API_VERSION", "2024-10-21"), http);
            case "gemini", "google" -> new GeminiClient(baseUrl, key(env, "GEMINI_API_KEY"),
                    required(model, "AI_MODEL"), http);
            case "openai-compatible", "ollama", "local" -> new OpenAiClient(OpenAiClient.Flavor.COMPATIBLE,
                    required(baseUrl, "AI_BASE_URL"), blankToNull(env.get("AI_API_KEY")),
                    required(model, "AI_MODEL"), null, http);
            case "host", "chat" -> HostClient.fromEnv(env);
            case "scripted" -> new ScriptedClient(Path.of(required(blankToNull(env.get("AI_SCRIPT")), "AI_SCRIPT")));
            default -> throw new Llm.LlmException("Unknown AI_PROVIDER '" + provider + "'. Use anthropic, openai, "
                    + "azure-openai, gemini, openai-compatible, host or scripted.");
        };
        return client;
    }

    private static String key(Map<String, String> env, String specific) {
        String value = blankToNull(env.get("AI_API_KEY"));
        if (value == null) value = blankToNull(env.get(specific));
        if (value == null) throw new Llm.LlmException("Missing API key. Set AI_API_KEY or " + specific + ".");
        Redactor.register(value);
        return value;
    }

    private static String required(String value, String name) {
        if (value == null) throw new Llm.LlmException("Missing required setting " + name + ".");
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
