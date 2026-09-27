package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 中间站站停登记项。到发均为 UTC 时刻，区间左闭右开 [arrivalUtc, departureUtc)；
 * 至少提供其一：仅发车表示始发（arrivalUtc 为 null），仅到达表示终到（departureUtc 为 null），
 * 两端齐全时发车必须晚于到达。
 *
 * @param trainNo      停靠列车编号
 * @param stationId    站点业务 ID
 * @param arrivalUtc   到达时刻（含），UTC；null 表示始发无到达
 * @param departureUtc 发车时刻（不含），UTC；null 表示终到无发车
 */
public record StationStopRequest(
        @NotBlank String trainNo,
        @NotBlank String stationId,
        Instant arrivalUtc,
        Instant departureUtc) {
}
