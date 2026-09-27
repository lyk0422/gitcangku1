package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续保障登记请求。requestKey 为幂等键；crossMidnight 明确是否跨零点
 * （false 同运营日，true 后车运营日为前车次日）。
 */
public record RegisterConnectionRequest(
        @NotBlank String requestKey,
        @NotBlank String predecessorScheduleKey,
        @NotBlank String successorScheduleKey,
        @NotBlank String transferSectionId,
        @NotNull Integer guaranteeMinutes,
        @NotNull Boolean crossMidnight) {
}
