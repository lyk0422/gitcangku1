package com.example.starter.bridge.api;

import java.time.Instant;

import com.example.starter.bridge.RejectReason;
import com.example.starter.bridge.RejectionRecord;

/**
 * 拒绝记录视图。
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
public record RejectionView(
        long id,
        String requestId,
        String plateNo,
        long massKg,
        Instant plannedEntryAt,
        RejectReason reason,
        String detail,
        Instant createdAt) {

    /**
     * 从领域对象构建视图。
     */
    public static RejectionView from(RejectionRecord record) {
        return new RejectionView(record.id(), record.requestId(), record.plateNo(), record.massKg(),
                record.plannedEntryAt(), record.reason(), record.detail(), record.createdAt());
    }
}