package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变首次披露快照记录：对应 disclosure_snapshot 表的一行，生成后不可变。
 *
 * @param observationId       观测记录唯一标识
 * @param winningVersion      发布时胜出版本号
 * @param location            胜出版本观测地点快照；删除墓碑为 null
 * @param reading             胜出版本观测读数快照（十进制原文）；删除墓碑为 null
 * @param note                胜出版本观测备注快照；删除墓碑为 null
 * @param deleted             胜出版本是否为删除墓碑
 * @param conflictResolutions 发布时全部冲突裁决记录（已按解决时刻升序）
 * @param qualityFlags        发布时质量标记
 * @param embargoUntilUtc     对应禁运截止时刻（UTC）
 * @param operator            执行发布的操作者标识
 * @param requestId           发布请求标识
 * @param releasedAtUtc       首次发布时刻（UTC）
 */
public record DisclosureSnapshotRecord(
        String observationId,
        int winningVersion,
        String location,
        String reading,
        String note,
        boolean deleted,
        List<DisclosureResolutionItem> conflictResolutions,
        List<String> qualityFlags,
        Instant embargoUntilUtc,
        String operator,
        String requestId,
        Instant releasedAtUtc) {
}
