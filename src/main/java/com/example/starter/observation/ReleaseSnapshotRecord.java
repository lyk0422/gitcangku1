package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变到期发布快照记录：对应 embargo_release 表的一行，首次公开发布时生成，永不修改。
 *
 * @param releaseId           发布快照标识
 * @param observationId       观测记录唯一标识（簇）
 * @param embargoUntilUtc     发布时禁运截止时刻（UTC）证据副本
 * @param disclosureScope     发布时披露范围证据副本（JSON 数组原文）
 * @param releasedVersion     发布时胜出版本号
 * @param location            胜出版本观测地点；墓碑版本为 null
 * @param reading             胜出版本观测读数（十进制原文）；墓碑版本为 null
 * @param note                胜出版本观测备注；墓碑版本为 null
 * @param deleted             发布时簇是否为删除墓碑
 * @param conflictResolutions 发布时全部冲突裁决摘要（JSON 数组原文）
 * @param qualityFlags        质量标记（JSON 数组原文）
 * @param releasedAtUtc       首次公开发布时刻（UTC）
 */
public record ReleaseSnapshotRecord(
        String releaseId,
        String observationId,
        Instant embargoUntilUtc,
        String disclosureScope,
        int releasedVersion,
        String location,
        String reading,
        String note,
        boolean deleted,
        String conflictResolutions,
        String qualityFlags,
        Instant releasedAtUtc) {
}
