package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量保障改签响应：每项改签后的新旧始发时刻与新版本，顺序与请求一致。
 */
public record GuaranteeRescheduleResponse(List<ItemResult> items) {

    /**
     * 单项改签结果。
     */
    public record ItemResult(String scheduleKey, Instant oldDepartureUtc, Instant newDepartureUtc,
                             int shiftMinutes, int version) {
    }
}