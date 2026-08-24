package io.github.billstark001.worldmirror.config;

import me.shedaniel.autoconfig.AutoConfigClient;
import me.shedaniel.autoconfig.util.Utils;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Installs a selection-only Cloth Config provider for every enum field. */
@Environment(EnvType.CLIENT)
public final class EnumDropdownGui {
    private static boolean registered;

    private EnumDropdownGui() { }

    /** Future enum fields receive the same interaction without another annotation. */
    public static synchronized void register() {
        if (registered) return;
        AutoConfigClient.getGuiRegistry(ModConfig.class).registerPredicateProvider(
                EnumDropdownGui::createEntry, field -> field.getType().isEnum());
        registered = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<AbstractConfigListEntry> createEntry(
            String translationKey, Field field, Object config, Object defaults, Object registry) {
        List<Enum> values = Arrays.asList((Enum[]) field.getType().getEnumConstants());
        Enum current = Utils.getUnsafely(field, config);
        Function<Enum, Component> label = value -> valueLabel(translationKey, value);

        ClosingDropdownEntry<Enum> entry = new ClosingDropdownEntry<>(
                Component.translatable(translationKey),
                ConfigEntryBuilder.create().getResetButtonKey(),
                Optional::empty,
                () -> Utils.getUnsafely(field, defaults),
                value -> Utils.setUnsafely(field, config, value),
                values,
                EnumDropdownGuiApi.createTopCell(current, label),
                DropdownMenuBuilder.CellCreatorBuilder.of(label));
        return Collections.singletonList(entry);
    }

    @SuppressWarnings("rawtypes")
    private static Enum findByLabel(List<Enum> values, String translationKey, String input) {
        for (Enum value : values) {
            if (valueLabel(translationKey, value).getString().equals(input)) return value;
        }
        return null;
    }

    /** Uses AutoConfig's field-specific value keys, with a readable fallback for future enums. */
    public static Component valueLabel(String translationKey, Enum<?> value) {
        String valueKey = translationKey + "." + value.name().toLowerCase(Locale.ROOT);
        if (!I18n.get(valueKey).equals(valueKey)) return Component.translatable(valueKey);
        String[] words = value.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder fallback = new StringBuilder();
        for (String word : words) {
            if (!fallback.isEmpty()) fallback.append(' ');
            fallback.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.literal(fallback.toString());
    }

}
