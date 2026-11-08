package com.medirecords.audit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One changed field, with values as display strings (dates as ISO, enums by name). {@code oldValue} is
 * null for a created field, {@code newValue} for a deleted one.
 */
public record FieldChange(String field, String oldValue, String newValue) {

    /**
     * Compares two snapshots of the same record type field by field. Pass {@code null} as {@code before}
     * for a creation or as {@code after} for a deletion. Fields that did not change are left out.
     *
     * <p>Working on the record's components means a field added to the snapshot type is audited
     * automatically, with no list of field names to keep in sync.
     */
    public static <T extends Record> List<FieldChange> between(T before, T after) {
        T sample = before != null ? before : after;
        if (sample == null) {
            return List.of();
        }
        List<FieldChange> changes = new ArrayList<>();
        for (RecordComponent component : sample.getClass().getRecordComponents()) {
            String oldValue = valueOf(component, before);
            String newValue = valueOf(component, after);
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new FieldChange(component.getName(), oldValue, newValue));
            }
        }
        return changes;
    }

    private static String valueOf(RecordComponent component, Record record) {
        if (record == null) {
            return null;
        }
        try {
            Object value = component.getAccessor().invoke(record);
            return value == null ? null : value instanceof Enum<?> constant ? constant.name() : value.toString();
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Cannot read " + component.getName(), exception);
        }
    }
}
