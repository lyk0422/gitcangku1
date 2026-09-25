package com.example.starter.work.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 施工单视图。sectionIds 按字典序升序返回（集合换序视为同参）。
 */
public record WorkOrderResponse(
        String workKey,
        int version,
        String status,
        String operator,
        Instant startUtc,
        Instant endUtc,
        List<String> sectionIds) {
}
