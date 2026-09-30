package com.example.starter.salinity.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站位某深度当前被采纳的唯一版本视图。
 *
 * @param sampleNo      采样编号
 * @param stationCode   站位编号
 * @param depthM        深度，单位米
 * @param salinityPsu   盐度，单位 PSU
 * @param collectedUtc  采集 UTC 时刻
 */
public record AdoptedSampleView(
        String sampleNo,
        String stationCode,
        Integer depthM,
        BigDecimal salinityPsu,
        Instant collectedUtc
) {
}
