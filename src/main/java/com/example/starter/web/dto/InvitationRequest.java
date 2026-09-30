package com.example.starter.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 创建削峰邀约请求；时间为 UTC 左闭右开。
 *
 * @param periodStart   时段起点（含）
 * @param periodEnd     时段终点（不含）
 * @param rateFenPerKwh 每 kWh 奖励（分）；为空取默认 80
 */
public record InvitationRequest(
        @NotNull Instant periodStart,
        @NotNull Instant periodEnd,
        Integer rateFenPerKwh
) {
}
