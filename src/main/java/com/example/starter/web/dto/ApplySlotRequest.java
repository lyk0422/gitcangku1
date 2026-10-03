package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 申请除冰时隙请求。
 *
 * @param requestId 幂等请求号
 * @param vehicleId 除冰车 ID
 * @param flightId 航班 ID
 * @param startUtc 时隙开始（UTC，含）
 * @param endUtc 时隙结束（UTC，不含）
 */
public record ApplySlotRequest(
        @NotBlank String requestId,
        @NotNull Long vehicleId,
        @NotNull Long flightId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {
}