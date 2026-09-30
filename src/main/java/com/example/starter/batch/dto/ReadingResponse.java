package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 温度读数登记响应：读数本体 + 本次读数触发的偏差事件（越界/缺测，可能同时触发两类）
 * + 登记后批次门禁状态。GATE_CLOSED 表示批次存在未处置偏差，禁止放行、拆分和合批。
 */
public record ReadingResponse(
        String batchKey,
        String segmentKey,
        String requestId,
        Instant readingTime,
        BigDecimal temperatureCelsius,
        List<DeviationResponse> deviations,
        String gateStatus,
        int openDeviationCount,
        Instant createdAt
) {
}
