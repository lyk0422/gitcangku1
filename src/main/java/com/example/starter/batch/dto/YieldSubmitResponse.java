package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 产率登记/修订响应：本次落定的全部批次产率记录，按请求顺序返回。
 */
public record YieldSubmitResponse(
        String yieldKey,
        List<YieldRecordResponse> records,
        Instant submittedAt
) {
}
