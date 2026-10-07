package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次序号域查询响应。intervals 为该批次登记/拆分时落库的冻结区间，不随后续拆分改写；
 * current 为 false 表示该批已拆分（SPLIT），其区间仅作历史，当前归属在各叶批。
 */
public record BatchIntervalsResponse(
        String batchKey,
        String batchKind,
        Integer quantity,
        boolean current,
        List<IntervalView> intervals
) {
}
