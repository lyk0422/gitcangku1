package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 称量记录历史视图：含更正替代关系；supersededByWeighingId 非空表示该记录已被更正作废。
 */
public record WeighingRecordView(
        long weighingId,
        String ingredient,
        BigDecimal massGrams,
        String kind,
        Long replacesWeighingId,
        Long supersededByWeighingId,
        int chainVersion,
        String actorId,
        Instant createdAt
) {
}