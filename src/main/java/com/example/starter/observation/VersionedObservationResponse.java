package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 按版本读取观测记录的响应：同时给出更正链 V0 基线值与该版本视角下的字段有效值。
 * version=0 为更正链 V0（冲突裁决生效后指向的只读快照）；version≥1 为对应附录版本，
 * 旧版本查询始终返回当时值，不随新附录漂移。
 *
 * @param observationId       观测记录唯一标识
 * @param version            查询的版本号：0 为更正链 V0 基线，1 起为附录版本
 * @param resolutionId       前置冲突裁决记录标识
 * @param snapshotVersion    V0 基线快照在 observation_version 中的版本号（建链时裁决指向的 new_version）
 * @param operator           该版本的更正操作者；V0 为 null
 * @param correctedAtUtc     该版本的更正完成时刻（UTC）；V0 为 null
 * @param originalLocation   V0 基线观测地点（裁决生效值，只读）
 * @param originalReading    V0 基线观测读数（裁决生效值，只读）
 * @param originalNote       V0 基线观测备注（裁决生效值，只读）
 * @param effectiveLocation  该版本视角下的观测地点有效值
 * @param effectiveReading   该版本视角下的观测读数有效值
 * @param effectiveNote      该版本视角下的观测备注有效值
 * @param appendices         截至该版本（含）的附录差异链，按版本先后排序
 */
public record VersionedObservationResponse(
        String observationId,
        int version,
        String resolutionId,
        int snapshotVersion,
        String operator,
        Instant correctedAtUtc,
        String originalLocation,
        String originalReading,
        String originalNote,
        String effectiveLocation,
        String effectiveReading,
        String effectiveNote,
        List<AppendixEntry> appendices) {
}
