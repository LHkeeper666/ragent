

package com.nageoffer.ai.ragent.common.queue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.task-priority")
public class TaskPriorityProperties {

    @Valid
    private High high = new High();

    @Valid
    private Low low = new Low();

    @Valid
    private WaitEstimate waitEstimate = new WaitEstimate();

    @Data
    public static class High {
        @Min(0)
        private long maxFileCount = 3;

        @Min(0)
        private long maxFileSize = 1024 * 1024;
    }

    @Data
    public static class Low {
        @Min(0)
        private long minFileCount = 20;

        @Min(0)
        private long minFileSize = 100L * 1024 * 1024;
    }

    @Data
    public static class WaitEstimate {
        @Min(1)
        private long highSecondsPerTask = 5;

        @Min(1)
        private long mediumSecondsPerTask = 30;

        @Min(1)
        private long lowSecondsPerTask = 120;
    }
}
