package com.example.starter.salinity.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 封存剖面条目：封存瞬间对采纳样本的不可变快照。
 *
 * @param id             剖面条目主键
 * @param voyageCode     所属航次编号
 * @param station        站位编号
 * @param depthM         深度，单位米（封存深度固定为 0/50/100）
 * @param sampleId       被固化样本的主键（引用 samples.id，保留追溯关联）
 * @param sampleNo       被固化样本编号快照
 * @param salinityPsu    被固化盐度快照，单位 PSU
 * @param collectedAtUtc 被固化样本的采集时刻（UTC）
 * @param sealedAtUtc    封存固化时刻（UTC）
 */
public record SealedProfileEntry(Long id,
                                 String voyageCode,
                                 String station,
                                 Integer depthM,
                                 Long sampleId,
                                 String sampleNo,
                                 BigDecimal salinityPsu,
                                 Instant collectedAtUtc,
                                 Instant sealedAtUtc) {
}
