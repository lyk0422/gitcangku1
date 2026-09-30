package com.example.starter.salinity.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 盐度样本（原始样本 + 采纳标记）。同一航次/站位/深度可有多个重测版本，
 * 其中至多一个版本 {@code adopted=true}；未采纳的原始重测永久保留。
 *
 * @param id             样本主键，入库前为 {@code null}
 * @param voyageCode     所属航次编号
 * @param station        站位编号
 * @param depthM         采样深度，单位米，范围 0-500
 * @param salinityPsu    盐度，单位 PSU，范围 0-45
 * @param collectedAtUtc 采集时刻（UTC）
 * @param sampleNo       采样编号，全局唯一
 * @param requestId      客户端请求幂等键，全局唯一
 * @param adopted        是否为该位置当前质控采纳版本
 * @param versionNo      同位置重测版本号，从 1 开始
 * @param createdAtUtc   入库时刻（UTC）
 */
public record Sample(Long id,
                     String voyageCode,
                     String station,
                     Integer depthM,
                     BigDecimal salinityPsu,
                     Instant collectedAtUtc,
                     String sampleNo,
                     String requestId,
                     boolean adopted,
                     Integer versionNo,
                     Instant createdAtUtc) {

    /**
     * 返回带数据库主键的副本。
     */
    public Sample withId(long newId) {
        return new Sample(newId, voyageCode, station, depthM, salinityPsu, collectedAtUtc,
                sampleNo, requestId, adopted, versionNo, createdAtUtc);
    }
}
