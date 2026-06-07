
package com.nageoffer.ai.ragent.ingestion.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionTaskEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String priority;

    private String operator;
}
