package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 温度读数登记响应：回显原始读数、入库时生效限值快照及本次读数新生成的偏差事件键（无则为空列表）。
 * 限值快照不随后续改版回写。
 */
public record TemperatureReadingResponse(
        String requestId,
        String batchKey,
        String segmentKey,
        Instant readAt,
        BigDecimal temperature,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        boolean replay,
        List<String> excursionKeys,
        Instant recordedAt
) {
}
