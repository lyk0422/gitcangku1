package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 乘务换人请求：将风险门禁状态下计划的两角色一次性替换为合格人员并解除门禁。
 */
public record CrewReplacementRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotBlank String driverId,
        @NotBlank String conductorId) {
}
