package com.example.starter.observation;

import java.time.Instant;

/**
 * 簇内字段冲突登记：对应 bundle_conflict 表的一行。
 *
 * @param id                自增主键（联合裁决据此定位冲突）
 * @param bundleKey         所属簇标识
 * @param observationId     冲突所在观测标识
 * @param field             冲突字段名（location/reading/note）
 * @param baseVersion       离线候选所基于的基线版本号
 * @param candidateLocation 登记时离线候选地点完整值（候选快照）
 * @param candidateReading  登记时离线候选读数完整值（十进制原文）
 * @param candidateNote     登记时离线候选备注完整值（候选快照）
 * @param candidateToken    候选快照指纹：裁决请求必须原样回传
 * @param status            冲突状态（OPEN/RESOLVED）
 * @param resolvedSource    裁决采用来源；未关闭时为 null
 * @param resolvedValue     来源为 VALUE 时的显式新值原文；其他来源为 null
 * @param arbitrationId     关闭该冲突的联合裁决标识
 * @param resolvedAtUtc     关闭时刻（UTC）
 * @param createdAtUtc      登记时刻（UTC）
 */
public record BundleConflictRecord(
        long id,
        String bundleKey,
        String observationId,
        String field,
        int baseVersion,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        String candidateToken,
        BundleConflictStatus status,
        BundleSource resolvedSource,
        String resolvedValue,
        String arbitrationId,
        Instant resolvedAtUtc,
        Instant createdAtUtc) {
}
