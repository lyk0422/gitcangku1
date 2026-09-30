package com.example.starter.salinity.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单条样本视图（原始重测也全部保留可查）。
 *
 * @param sampleNo      采样编号
 * @param stationCode   站位编号
 * @param depthM        深度，单位米
 * @param salinityPsu   盐度，单位 PSU
 * @param collectedUtc  采集 UTC 时刻
 * @param adopted       是否为当前采纳版本
 * @param supersededBy  替换本样本的重测采样编号；未被替换为 {@code null}
 * @param requestId     提交时携带的请求 ID；未携带为 {@code null}
 */
public record SampleView(
        String sampleNo,
        String stationCode,
        Integer depthM,
        BigDecimal salinityPsu,
        Instant collectedUtc,
        boolean adopted,
        String supersededBy,
        String requestId
) {
}
