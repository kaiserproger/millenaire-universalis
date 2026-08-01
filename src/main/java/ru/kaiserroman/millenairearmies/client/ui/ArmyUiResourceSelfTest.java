package ru.kaiserroman.millenairearmies.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates paired locales and every literal addon UI translation referenced by Java sources. */
public final class ArmyUiResourceSelfTest {
    private static final Pattern TRANSLATION = Pattern.compile(
            "[\\\"]((?:gui|key|goal|presentation)\\.millenaire_armies\\.[a-z0-9_.-]+)[\\\"]");

    private ArmyUiResourceSelfTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected en_us, ru_ru and source root");
        JsonObject english = read(Path.of(args[0]));
        JsonObject russian = read(Path.of(args[1]));
        Set<String> englishKeys = english.keySet();
        Set<String> russianKeys = russian.keySet();
        check(englishKeys.equals(russianKeys), "en_us/ru_ru key sets differ: "
                + difference(englishKeys, russianKeys) + " / " + difference(russianKeys, englishKeys));
        validateValues("en_us", english);
        validateValues("ru_ru", russian);

        Set<String> referenced = new HashSet<>();
        try (var paths = Files.walk(Path.of(args[2]))) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> scan(path, referenced));
        }
        referenced.remove("gui.done");
        referenced.removeIf(key -> key.endsWith("."));
        Set<String> missing = difference(referenced, englishKeys);
        check(missing.isEmpty(), "Missing UI translations: " + missing);
        System.out.println("Army UI resources valid: " + englishKeys.size() + " paired keys, "
                + referenced.size() + " literal references");
    }

    private static JsonObject read(Path path) throws IOException {
        JsonElement element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        check(element.isJsonObject(), path + " is not a JSON object");
        return element.getAsJsonObject();
    }

    private static void validateValues(String locale, JsonObject object) {
        for (var entry : object.entrySet()) {
            check(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(),
                    locale + " non-string value: " + entry.getKey());
            String value = entry.getValue().getAsString();
            String normalized = value.toLowerCase(Locale.ROOT);
            check(!value.isBlank(), locale + " blank value: " + entry.getKey());
            check(!normalized.contains("todo") && !normalized.contains("placeholder"),
                    locale + " unfinished value: " + entry.getKey());
        }
    }

    private static void scan(Path path, Set<String> destination) {
        try {
            Matcher matcher = TRANSLATION.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (matcher.find()) destination.add(matcher.group(1));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot scan " + path, exception);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
