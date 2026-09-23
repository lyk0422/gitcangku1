package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 联合裁决响应：返回不可变裁决记录要点、逐字段来源、墓碑恢复依据与簇级前后快照（均按观测、字段稳定排序）。
 *
 * @param arbitrationId 全局唯一联合裁决记录标识
 * @param bundleKey     被裁决簇标识
 * @param requestId     生成该裁决的请求标识
 * @param surveyId      簇所属调查问卷标识
 * @param operator      审核员标识
 * @param arbitratedAtUtc 裁决完成时刻（UTC，ISO-8601）
 * @param conflictResolutions 每个冲突字段的裁决来源与最终值（按观测、字段排序）
 * @param restoredObservations 被恢复墓碑的恢复依据（按观测排序）
 * @param snapshotBefore 裁决前簇级成员快照（按观测排序）
 * @param snapshotAfter  裁决后簇级成员快照（按观测排序）
 */
public record ArbitrationResponse(
        String arbitrationId,
        String bundleKey,
        String requestId,
        String surveyId,
        String operator,
        Instant arbitratedAtUtc,
        List<ConflictResolutionView> conflictResolutions,
        List<RestoreView> restoredObservations,
        List<MemberSnapshotView> snapshotBefore,
        List<MemberSnapshotView> snapshotAfter) {

    /**
     * 单冲突字段的裁决结果。
     *
     * @param conflictId    冲突登记 id
     * @param observationId 冲突所在观测标识
     * @param field         字段名
     * @param source        最终采用来源：LOCAL/REMOTE/BASE/VALUE
     * @param value         该字段最终值（VALUE 为显式新值原文；读数为十进制原文）
     */
    public record ConflictResolutionView(
            long conflictId,
            String observationId,
            String field,
            String source,
            String value) {
    }

    /**
     * 墓碑恢复依据视图。
     *
     * @param observationId 被恢复观测标识
     * @param fields        各必填字段的恢复来源与取值（按字段固定顺序）
     */
    public record RestoreView(
            String observationId,
            List<RestoreFieldView> fields) {
    }

    /**
     * 墓碑恢复单字段依据。
     *
     * @param field  字段名
     * @param source 来源：BASE（末个存活版本）/ VALUE（显式新值）
     * @param value  恢复取值
     */
    public record RestoreFieldView(
            String field,
            String source,
            String value) {
    }

    /**
     * 簇级成员快照视图（裁决前/后）。
     *
     * @param observationId 成员观测标识
     * @param role          成员角色（裁决后墓碑恢复成员仍为 ACTIVE 语义，角色字段保留建簇角色）
     * @param version       该成员版本号
     * @param deleted       是否为墓碑
     * @param location      地点；墓碑时省略
     * @param reading       读数；墓碑时省略
     * @param note          备注；墓碑时省略
     */
    public record MemberSnapshotView(
            String observationId,
            String role,
            int version,
            boolean deleted,
            String location,
            String reading,
            String note) {
    }
}
