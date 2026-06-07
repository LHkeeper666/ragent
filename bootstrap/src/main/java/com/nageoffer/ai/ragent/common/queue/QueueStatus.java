
package com.nageoffer.ai.ragent.common.queue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum QueueStatus {

    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    @JsonCreator
    public static QueueStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (QueueStatus status : values()) {
            if (status.value.equals(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown queue status: " + value);
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
