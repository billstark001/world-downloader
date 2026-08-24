package io.github.billstark001.worldmirror.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumTranslationCoverageTest {
    private static final List<String> LOCALES = List.of("en_us", "zh_cn", "zh_tw", "ja_jp");
    private static final String ROOT_KEY = "text.autoconfig.worldmirror.option";

    @Test
    void everyConfigEnumChoiceHasAFieldSpecificTranslationInEveryLocale() throws Exception {
        for (String locale : LOCALES) {
            String resource = "assets/worldmirror/lang/" + locale + ".json";
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream, "Missing language resource " + resource);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEnumTranslations(ModConfig.class, ROOT_KEY, translations, locale);
            }
        }
    }

    private static void assertEnumTranslations(
            Class<?> configType, String prefix, JsonObject translations, String locale) {
        for (Field field : configType.getDeclaredFields()) {
            if (field.getType().isEnum()) {
                for (Object constant : field.getType().getEnumConstants()) {
                    String value = ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
                    String key = prefix + "." + field.getName() + "." + value;
                    assertTrue(translations.has(key), () -> "Missing " + locale + " key " + key);
                }
            } else if (field.isAnnotationPresent(ConfigEntry.Gui.CollapsibleObject.class)) {
                assertEnumTranslations(field.getType(), prefix + "." + field.getName(),
                        translations, locale);
            }
        }
    }
}
