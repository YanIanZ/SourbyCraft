package ca.spottedleaf.yamlconfig.adapter.type;

import ca.spottedleaf.common.time.CalenderDuration;
import ca.spottedleaf.yamlconfig.adapter.generic.StringFormTypeAdapter;

public final class CalenderDurationTypeAdapter extends StringFormTypeAdapter<CalenderDuration> {

    public static final CalenderDurationTypeAdapter INSTANCE = new CalenderDurationTypeAdapter();

    @Override
    public CalenderDuration fromString(final String value) {
        return CalenderDuration.parse(value);
    }

    @Override
    public String toString(final CalenderDuration value) {
        return value.getParsedForm();
    }
}
