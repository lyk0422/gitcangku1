package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变首次披露快照记录：对应 disclosure_snapshot 表的一行，发布后永不修改。
 *
 * @param observationId    观测记录唯一标识
 * @param winningVersion   发布时点胜出（当前）版本号
 * @param location         发布时点胜出版本观测地点；删除墓碑为 null
 * @param reading          发布时点胜出版本观测读数（十进制原文）；删除墓碑为 null
 * @param note             发布时点胜出版本观测备注；删除墓碑为 null
 * @param deleted          发布时点当前状态是否为删除墓碑
 * @param resolutionsJson  发布时点冲突裁决摘要（JSON 数组原文，按解决时刻升序）
 * @param qualityFlagsJson 发布时点质量标记（JSON 数组原文）
 * @param releaseRequestId 触发首次发布的请求标识
 * @param operator         执行首次发布的操作者标识
 * @param releasedAtUtc    首次公开发布时刻（UTC）
 */
public record DisclosureSnapshotRecord(
        String observationId,
        int winningVersion,
        String location,
        String reading,
        String note,
        boolean deleted,
        String resolutionsJson,
        String qualityFlagsJson,
        String releaseRequestId,
        String operator,
        Instant releasedAtUtc) {
}