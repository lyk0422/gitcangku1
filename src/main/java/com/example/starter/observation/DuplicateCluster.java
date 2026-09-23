package com.example.starter.observation;

import java.time.Instant;

/**
 * 重复观测簇记录：对应 duplicate_cluster 表的一行，归并确认成功后原子落库，不可变。
 *
 * @param clusterKey          审核人提交的簇标识，全局唯一
 * @param canonicalRecordKey  归并生成的新主记录键
 * @param siteKey             簇分组站点键（全部成员一致）
 * @param obsType             簇分组观测类型（全部成员一致）
 * @param windowStart         确认时重算的成员最小 observedAt（UTC）
 * @param windowEnd           确认时重算的成员最大 observedAt（UTC）
 * @param memberCount         成员记录数量（2～20）
 * @param requestId           生成该簇的请求标识
 */
public record DuplicateCluster(
        String clusterKey,
        String canonicalRecordKey,
        String siteKey,
        String obsType,
        Instant windowStart,
        Instant windowEnd,
        int memberCount,
        String requestId) {
}
