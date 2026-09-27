package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 中间站到发登记请求项，区间左闭右开 [arrivalUtc, departureUtc)，时刻均为 UTC。
 * 到发倒置与停留时长在发布时按站台配置裁决；同一计划内同一列车同一站只能登记一次。
 */
public record StationDwellRequest(
        @NotBlank String trainNo,
        @NotBlank String stationId,
        @NotNull Instant arrivalUtc,
        @NotNull Instant departureUtc) {
}
