package me.zeroeightsix.kami.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import me.zeroeightsix.kami.setting.converter.Convertable;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Created by 086 on 12/10/2018.
 */
public abstract class Setting<T> implements ISettingUnknown, Convertable<T> {

    private final String name;
    private final T defaultValue;
    private final Class<?> valueType;
    /**
     * Returns false if the value is "out of bounds"
     */
    private final Predicate<T> restriction;
    private final Predicate<T> visibilityPredicate;
    private final BiConsumer<T, T> consumer;
    public SettingListeners settingListener;
    private T value;

    public Setting(T value, Predicate<T> restriction, BiConsumer<T, T> consumer, String name, Predicate<T> visibilityPredicate) {
        this.name = name;
        this.value = value;
        this.defaultValue = value;
        this.valueType = value.getClass();
        this.restriction = restriction;
        this.visibilityPredicate = visibilityPredicate;
        this.consumer = consumer;
    }

    @Override
    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    @Override
    public Class<?> getValueClass() {
        return valueType;
    }

    /**
     * @param value
     * @return true if value was set
     */
    public boolean setValue(T value) {
        T old = getValue();
        if (!restriction.test(value))
            return false;
        this.value = value;
        consumer.accept(old, value);
        if (settingListener != null)
            settingListener.onSettingChange();
        return true;
    }

    /**
     * Reset value to default
     */
    public void resetValue() {
        this.value = defaultValue;
    }

    @Override
    public boolean isVisible() {
        return visibilityPredicate.test(getValue());
    }

    /**
     * @return A consumer that expects first the previous value and then the new value
     */
    public BiConsumer<T, T> changeListener() {
        return consumer;
    }

    @Override
    public void setValueFromString(String value, boolean isBoolean) {
        JsonParser jp = new JsonParser();
        if (isBoolean && value.equalsIgnoreCase("toggle") && this.getValue().equals(true)) {
            setValue(this.converter().reverse().convert(jp.parse("false")));
            return;
        } else if (isBoolean && value.equalsIgnoreCase("toggle")) {
            setValue(this.converter().reverse().convert(jp.parse("true")));
            return;
        }
        setValue(this.converter().reverse().convert(jp.parse(value)));
    }

    @Override
    public String toString() {
        JsonElement converted = this.converter().convert(getValue());
        if (converted != null) {
            return converted.toString();
        } else {
            return "";
        }
    }

    public interface SettingListeners {
        void onSettingChange();
    }
}
