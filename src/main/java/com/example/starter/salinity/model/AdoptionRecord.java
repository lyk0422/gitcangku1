package com.example.starter.salinity.model;

import java.time.Instant;

/**
 * 质控采纳操作记录：用于质控请求的幂等重放。
 *
 * @param id           记录主键
 * @param requestId    质控请求幂等键，全局唯一
 * @param voyageCode   质控操作所属航次编号
 * @param station      采纳版本所在站位
 * @param depthM       采纳版本所在深度，单位米
 * @param sampleId     被采纳样本主键
 * @param adoptedAtUtc 采纳操作时刻（UTC）
 */
public record AdoptionRecord(Long id,
                             String requestId,
                             String voyageCode,
                             String station,
                             Integer depthM,
                             Long sampleId,
                             Instant adoptedAtUtc) {
}
