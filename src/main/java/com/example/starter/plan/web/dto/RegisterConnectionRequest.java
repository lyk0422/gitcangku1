package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续登记请求。requestKey 为幂等键；guaranteeMinutes 范围 0~1440（越界返回 422）；
 * crossMidnight 为 true 时允许后车始发落在前车运营日次日。
 */
public record RegisterConnectionRequest(
        @NotBlank String requestKey,
        @NotBlank String connectionKey,
        @NotBlank String predecessorScheduleKey,
        @NotBlank String successorScheduleKey,
        @NotNull Integer guaranteeMinutes,
        boolean crossMidnight) {
}