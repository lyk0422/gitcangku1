package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续登记请求。requestKey 为幂等键；guaranteeMinutes 合法范围 5~240（超出返回 422）；
 * crossesMidnight 为 true 时允许后车在下一运营日（明确跨零点），缺省 false。
 */
public record RegisterConnectionRequest(
        @NotBlank String requestKey,
        @NotBlank String predecessorKey,
        @NotBlank String successorKey,
        @NotNull Integer guaranteeMinutes,
        Boolean crossesMidnight) {
}