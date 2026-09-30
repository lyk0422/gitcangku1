package com.example.starter.salinity.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冻结剖面中的一个深度点；未封存时该列表为空。
 *
 * @param stationCode   站位编号
 * @param depthM        深度，单位米
 * @param sampleNo      固化的采纳样本编号
 * @param salinityPsu   固化盐度，单位 PSU
 * @param collectedUtc  采集 UTC 时刻
 */
public record FrozenProfilePointView(
        String stationCode,
        Integer depthM,
        String sampleNo,
        BigDecimal salinityPsu,
        Instant collectedUtc
) {
}
