package com.example.starter.observation;

import java.time.Instant;

/**
 * 冻结快照头：对应 observation_snapshot 表的一行，创建后不可变、永不更新或删除。
 *
 * @param snapshotKey         全局唯一快照标识
 * @param targetTimeUtc       快照目标 UTC 时刻
 * @param globalLatestVersion 读取一致状态时的全局最新版本（跨全部观测记录单调递增）
 * @param createdAtUtc        快照创建完成时刻（UTC）
 */
public record SnapshotHeader(
        String snapshotKey,
        Instant targetTimeUtc,
        long globalLatestVersion,
        Instant createdAtUtc) {
}
