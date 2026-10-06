package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 称量/更正响应。weighingId 为新记录 ID；correctedWeighingId 为被替代的称量 ID（非更正为 null）；
 * totals 为落定后各成分对照；conforms 为当前是否已满足配方放行条件。
 */
public record WeighingResponse(
        Long weighingId,
        Long correctedWeighingId,
        String batchKey,
        String ingredient,
        BigDecimal mass,
        Long stateVersion,
        List<IngredientTotal> totals,
        boolean conforms,
        Instant weighedAt
) {
}
