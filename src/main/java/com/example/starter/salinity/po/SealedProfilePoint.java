package com.example.starter.salinity.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 已冻结剖面的一个深度点，封存时刻快照写入，此后不再变化。
 *
 * @param id           自增主键
 * @param voyageCode   航次编号，非空
 * @param stationCode  站位编号，非空
 * @param depthM       深度，单位米；封存固化标准层 0、50、100
 * @param sampleId     被固化的采纳样本主键，非空
 * @param salinityPsu  固化时的盐度，单位 PSU
 * @param collectedUtc 样本采集 UTC 时刻
 * @param sealedUtc    本航次封存 UTC 时刻，非空
 */
public record SealedProfilePoint(
        Long id,
        String voyageCode,
        String stationCode,
        Integer depthM,
        Long sampleId,
        BigDecimal salinityPsu,
        Instant collectedUtc,
        Instant sealedUtc
) {
}
