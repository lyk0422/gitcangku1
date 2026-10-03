package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接续登记请求。requestKey 为幂等键；guaranteeMinutes 合法范围 0~1440
 * （越界由服务层返回 422）；同运营日 crossesMidnight 为 false，
 * 后车运营日为前车次日时必须为 true。
 */
public record ConnectionRegisterRequest(
        @NotBlank String requestKey,
        @NotBlank String predecessorKey,
        @NotBlank String successorKey,
        @NotNull Integer guaranteeMinutes,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @NotNull Boolean crossesMidnight) {
}