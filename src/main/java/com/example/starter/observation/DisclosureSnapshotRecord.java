package com.example.starter.observation;

import java.time.Instant;

/**
 * 披露快照记录：对应 disclosure_snapshot 表的一行，首次发布后不可变。
 *
 * @param observationId       观测记录唯一标识
 * @param version             发布时胜出版本号
 * @param location            胜出版本观测地点；删除墓碑为 null
 * @param reading             胜出版本观测读数；删除墓碑为 null
 * @param note                胜出版本观测备注；删除墓碑为 null
 * @param deleted             发布时是否为删除墓碑
 * @param resolutionId        最近一次冲突解决记录标识；无解决历史为 null
 * @param conflictFieldsJson  最近一次裁决的冲突字段名列表（JSON 原文）；无解决历史为 null
 * @param fieldSelectionsJson 最近一次裁决的人工选择（JSON 原文）；无解决历史为 null
 * @param qualityFlagsJson    质量标记（JSON 数组原文）
 * @param operator            首次发布的操作者标识
 * @param requestId           首次发布的请求标识
 * @param releasedAtUtc       首次发布时刻（UTC）
 */
public record DisclosureSnapshotRecord(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String resolutionId,
        String conflictFieldsJson,
        String fieldSelectionsJson,
        String qualityFlagsJson,
        String operator,
        String requestId,
        Instant releasedAtUtc) {
}