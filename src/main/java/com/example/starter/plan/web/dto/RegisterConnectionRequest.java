package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续登记请求：在两趟已发布计划间建立接续保障关系。
 * requestKey 为幂等键；guaranteeMinutes 合法范围 0~720（越界返回 422）；
 * crossMidnight 为 true 表示明确跨零点接续（后车运营日 = 前车运营日 + 1）。
 */
public record RegisterConnectionRequest(
        @NotBlank String requestKey,
        @NotBlank String predecessorScheduleKey,
        @NotBlank String successorScheduleKey,
        @NotNull Integer guaranteeMinutes,
        boolean crossMidnight) {
}
