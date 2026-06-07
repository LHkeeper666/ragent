
package com.nageoffer.ai.ragent.common.queue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TaskPriority {

    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    @JsonCreator
    public static TaskPriority fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (TaskPriority priority : values()) {
            if (priority.value.equals(normalized) || priority.name().equalsIgnoreCase(normalized)) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Unknown task priority: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
