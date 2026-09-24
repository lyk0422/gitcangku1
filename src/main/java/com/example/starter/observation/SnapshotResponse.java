package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 冻结快照响应：返回不可变快照头信息与按 observationId 升序固化的逐条内容。
 * 重复读取内容稳定；快照生成后的合并、冲突解决、删除与恢复均不改写它。
 *
 * @param snapshotKey         全局唯一冻结快照标识
 * @param targetTimeUtc       快照目标 UTC 时刻
 * @param globalLatestVersion 读取切刻处全局最新版本序号：当时已提交的全部观测版本总数（跨所有记录）
 * @param items               按 observationId 升序的逐条固化内容
 */
public record SnapshotResponse(
        String snapshotKey,
        Instant targetTimeUtc,
        long globalLatestVersion,
        List<Item> items) {

    /**
     * 由不可变慢照记录构造响应。
     */
    public static SnapshotResponse of(SnapshotRecord record) {
        List<Item> items = record.items().stream().map(Item::of).toList();
        return new SnapshotResponse(record.snapshotKey(), record.targetTimeUtc(),
                record.globalLatestVersion(), items);
    }

    /**
     * 快照逐条内容：ABSENT 不带 version 与业务字段，DELETED 不带业务字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String observationId,
            String state,
            Integer version,
            String location,
            String reading,
            String note,
            String lastResolutionId) {

        public static Item of(SnapshotItemRecord record) {
            return new Item(record.observationId(), record.state().name(), record.version(),
                    record.location(), record.reading(), record.note(), record.lastResolutionId());
        }
    }
}
