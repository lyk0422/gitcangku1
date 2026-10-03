package com.example.starter.well;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 切换审核所用采样快照，批准时冻结，原采样后续作废不影响本快照。
 *
 * @param sampleId 采样主键
 * @param pressureMpa 冻结的压力读数，单位 MPa
 * @param sampledAt 冻结的采样时刻（UTC）
 */
public record SampleSnapshot(long sampleId, BigDecimal pressureMpa, Instant sampledAt) {
}