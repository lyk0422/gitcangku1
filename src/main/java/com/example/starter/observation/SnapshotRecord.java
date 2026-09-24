package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 冻结快照主记录：对应 observation_snapshot 表的一行，写入后不可变。
 *
 * @param snapshotKey         全局唯一冻结快照标识
 * @param requestId           生成该快照的请求标识
 * @param targetTimeUtc       快照目标 UTC 时刻
 * @param globalLatestVersion 读取切刻处全局最新版本序号：当时已提交的全部观测版本总数（跨所有记录）
 * @param idFingerprint       归一化 observationId 集合指纹（升序、去重后哈希），用于同键异参判定
 * @param items               按 observationId 升序的逐条固化内容
 */
public record SnapshotRecord(
        String snapshotKey,
        String requestId,
        Instant targetTimeUtc,
        long globalLatestVersion,
        String idFingerprint,
        List<SnapshotItemRecord> items) {

    /**
     * 去重后 observationId 数量。
     */
    public int idCount() {
        return items.size();
    }
}
