package dev.yanianz.intave.block.variant.convert;

import dev.yanianz.intave.block.variant.Setting;
import dev.yanianz.intave.block.variant.Settings;
import dev.yanianz.intave.integration.NativeBlockStates;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Kept under the internal upstream class name; this adapter targets only native 26.2. */
final class v16ConversionBridge implements ConversionBridge {
    @Override public Map<Setting<?>, Comparable<?>> settingsOf(Object raw) {
        BlockState state = NativeBlockStates.require(raw);
        Map<Setting<?>, Comparable<?>> settings = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            Comparable<?> value = state.getValue(property);
            settings.put(SettingCache.computeSettingIfAbsent(property, this::convert),
                         value instanceof Enum<?> enumeration ? enumeration.name() : value);
        }
        return settings;
    }

    private Setting<?> convert(Object raw) {
        Property<?> property = (Property<?>) raw;
        if (property instanceof BooleanProperty) return Settings.booleanSetting(property.getName());
        if (property instanceof IntegerProperty integers) {
            var values = integers.getPossibleValues().stream().mapToInt(Integer::intValue).summaryStatistics();
            return Settings.integerSetting(property.getName(), values.getMin(), values.getMax());
        }
        if (property instanceof EnumProperty<?>)
            return Settings.enumSetting(property.getName(), property.getValueClass(), property.getPossibleValues());
        throw new IllegalStateException("Unsupported native property: " + property);
    }
}
