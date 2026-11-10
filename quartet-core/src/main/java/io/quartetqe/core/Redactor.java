package io.quartetqe.core;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Removes known secret values from anything sent to an AI provider or written to reports and caches. */
public final class Redactor {
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();

    private Redactor() {}

    public static void register(String secret) {
        if (secret != null && secret.length() >= 6) SECRETS.add(secret);
    }

    public static String clean(String text) {
        if (text == null) return null;
        String result = text;
        List<String> ordered = SECRETS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String secret : ordered) result = result.replace(secret, "***");
        return result;
    }
}
