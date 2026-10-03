package com.example.starter.web.dto;

import com.example.starter.domain.DeicingCompletion;
import java.time.Instant;

/** 完成记录视图，deadlineAt为起飞截止时刻（UTC，不含）。 */
public record CompletionResponse(long completionId, long slotId, String fluidType,
        Instant completedAt, Instant deadlineAt) {

    public static CompletionResponse of(DeicingCompletion completion) {
        return new CompletionResponse(completion.id(), completion.slotId(),
                completion.fluidType().name(), completion.completedAt(), completion.deadlineAt());
    }
}