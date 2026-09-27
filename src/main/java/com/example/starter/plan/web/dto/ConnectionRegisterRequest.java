package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续登记请求：在两趟已发布计划间登记换乘接续。requestKey 为幂等键；
 * crossMidnight 为 null 时按 false 处理，前后车运营日相邻（跨零点）时必须显式传 true。
 */
public record ConnectionRegisterRequest(
        @NotBlank String requestKey,
        @NotBlank String predecessorKey,
        @NotBlank String successorKey,
        @NotBlank String transferStation,
        @NotNull Integer guaranteeMinutes,
        Boolean crossMidnight) {
}
