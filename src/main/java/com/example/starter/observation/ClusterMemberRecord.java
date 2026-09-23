package com.example.starter.observation;

import java.time.Instant;

/**
 * 簇成员冻结快照行：对应 cluster_member 表，归并时每条成员记录的代次、设备、时间、字段值原样留存。
 *
 * @param clusterKey      所属归并簇标识
 * @param recordId        成员观测记录键
 * @param generation      成员代次
 * @param siteKey         成员站点标识
 * @param observationType 成员观测类型
 * @param deviceId        成员采集设备标识，可为空
 * @param observedAt      成员观测发生时刻（UTC）
 * @param location        成员观测地点冻结值
 * @param reading         成员观测读数冻结值
 * @param note            成员观测备注冻结值
 */
public record ClusterMemberRecord(
        String clusterKey,
        String recordId,
        int generation,
        String siteKey,
        String observationType,
        String deviceId,
        Instant observedAt,
        String location,
        String reading,
        String note) {
}
