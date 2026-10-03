package com.example.starter.bridge.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 许可签发申请；requestId 为幂等键，同参重放返回原许可，异参返回 409。
 */
public record ApplyPermitRequest(
        /** 签发幂等请求键。 */
        @NotBlank @Size(max = 64) String requestId,
        /** 车牌代号。 */
        @NotBlank @Size(max = 32) String plateNo,
        /** 车辆总质量，单位千克。 */
        @NotNull @Positive Integer totalMassKg,
        /** 预计入桥时刻，UTC。 */
        @NotNull Instant plannedEntryAt) {
}