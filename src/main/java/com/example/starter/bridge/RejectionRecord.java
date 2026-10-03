package com.example.starter.bridge;

import java.time.Instant;

/**
 * 申请拒绝记录（对应 bridge_rejection 表）。被拒申请不占用签发幂等键。
 *
 * @param id 拒绝记录主键
 * @param requestId 被拒申请的幂等键
 * @param plateNo 车牌代号
 * @param massKg 车辆总质量，单位千克
 * @param plannedEntryAt 预计入桥时刻，UTC
 * @param reason 拒绝原因
 * @param detail 拒绝依据明细
 * @param createdAt 记录创建时间，UTC
 */
public record RejectionRecord(
        long id,
        String requestId,
        String plateNo,
        long massKg,
        Instant plannedEntryAt,
        RejectReason reason,
        String detail,
        Instant createdAt) {
}