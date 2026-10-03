package com.example.starter.bridge.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 签发申请请求。
 *
 * @param requestId 签发幂等键
 * @param plateNo 车牌代号
 * @param massKg 车辆总质量，单位千克
 * @param plannedEntryAt 预计入桥时刻，UTC
 */
public record ApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String plateNo,
        @NotNull @Positive Long massKg,
        @NotNull Instant plannedEntryAt) {
}