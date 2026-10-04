package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变披露快照记录：对应 disclosure_snapshot 表的一行，首次发布后永不修改。
 *
 * @param observationId 观测记录唯一标识
 * @param version       发布时刻胜出版本号
 * @param location      发布时刻胜出观测地点快照；删除墓碑为 null
 * @param reading       发布时刻胜出观测读数快照（十进制原文）；删除墓碑为 null
 * @param note          发布时刻胜出观测备注快照；删除墓碑为 null
 * @param deleted       发布时刻记录是否为删除墓碑
 * @param resolutions   发布时刻全部冲突裁决摘要列表（JSON 数组原文，按解决时刻升序）
 * @param qualityFlags  质量标记列表（JSON 数组原文，按字典序排序）
 * @param operator      执行首次发布的操作者标识
 * @param requestId     首次发布的请求标识
 * @param releasedAtUtc 首次发布时刻（UTC）
 */
public record DisclosureSnapshotRecord(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String resolutions,
        String qualityFlags,
        String operator,
        String requestId,
        Instant releasedAtUtc) {
}