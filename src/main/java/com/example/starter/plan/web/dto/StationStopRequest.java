package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 站停登记请求项。到发区间解释为左闭右开 [arrivalUtc, departureUtc)；
 * 允许单端为 null：仅发车表示始发、仅到达表示终到；二者同时给出时发车必须严格晚于到达。
 */
public record StationStopRequest(
        @NotBlank String trainNo,
        @NotBlank String stationId,
        Instant arrivalUtc,
        Instant departureUtc) {
}
