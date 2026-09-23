package com.example.starter.observation;

import java.time.Instant;

/**
 * 簇成员冻结证据：对应 cluster_member 表的一行，记录归并确认时该成员被冻结的完整状态，不可变。
 *
 * @param clusterKey 所属簇标识
 * @param recordKey  成员观测记录键
 * @param generation 冻结的成员 generation（当时 version）
 * @param deviceId   冻结的成员采集设备标识
 * @param observedAt 冻结的成员观测发生时刻（UTC）
 * @param location   冻结的成员地点字段值
 * @param reading    冻结的成员读数字段值（十进制原文）
 * @param note       冻结的成员备注字段值
 * @param ordinal    成员在审核人提交集合中的原始序号
 */
public record ClusterMemberEvidence(
        String clusterKey,
        String recordKey,
        int generation,
        String deviceId,
        Instant observedAt,
        String location,
        String reading,
        String note,
        int ordinal) {
}
