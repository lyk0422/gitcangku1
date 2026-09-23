package com.example.starter.observation;

import java.time.Instant;

/**
 * 重复观测簇归并结果表头：对应 duplicate_cluster 表的一行，归并成功后原子落库，永不修改。
 *
 * @param clusterKey        归并簇标识，全局唯一
 * @param canonicalRecordId 归并产生的 canonical 主记录键
 * @param siteKey           站点标识
 * @param observationType   观测类型
 * @param observedAtFrom    确认时重算的成员观测时间范围下限（UTC）
 * @param observedAtTo      确认时重算的成员观测时间范围上限（UTC）
 * @param memberCount       成员记录数
 * @param requestId         生成该归并的请求标识
 * @param operator          执行归并的审核人标识
 * @param createdAt         落库时刻（UTC）
 */
public record ClusterHeader(
        String clusterKey,
        String canonicalRecordId,
        String siteKey,
        String observationType,
        Instant observedAtFrom,
        Instant observedAtTo,
        int memberCount,
        String requestId,
        String operator,
        Instant createdAt) {
}
