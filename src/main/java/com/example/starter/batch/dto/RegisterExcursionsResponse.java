package com.example.starter.batch.dto;

import java.util.List;

/**
 * 偏差登记响应：返回本次落库的完整偏差条目（严重级别已按批次规格判定）。
 */
public record RegisterExcursionsResponse(
        String batchKey,
        long batchVersion,
        List<ExcursionResponse> excursions
) {
}
