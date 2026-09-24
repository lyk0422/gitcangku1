package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 冻结快照响应：返回不可变快照头与按 observationId 升序固化的逐条内容；重复读取内容稳定。
 *
 * @param snapshotKey         全局唯一快照标识
 * @param targetTimeUtc       快照目标 UTC 时刻
 * @param globalLatestVersion 读取时的全局最新版本
 * @param createdAtUtc        快照创建完成时刻（UTC）
 * @param items               逐条固化内容
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotResponse(
        String snapshotKey,
        Instant targetTimeUtc,
        long globalLatestVersion,
        Instant createdAtUtc,
        List<Item> items) {

    /**
     * 快照内单条记录的固化视图。
     *
     * @param observationId    观测记录唯一标识
     * @param state            目标时刻状态：ACTIVE / TOMBSTONE / ABSENT
     * @param version          目标时刻最后一个版本号；ABSENT 时省略
     * @param deleted          是否处于删除墓碑状态
     * @param location         固化的观测地点；墓碑与 ABSENT 时省略
     * @param reading          固化的观测读数；墓碑与 ABSENT 时省略
     * @param note             固化的观测备注；墓碑与 ABSENT 时省略
     * @param lastResolutionId 目标时刻之前最近一次冲突解决记录标识；无则省略
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String observationId,
            String state,
            Integer version,
            boolean deleted,
            String location,
            String reading,
            String note,
            String lastResolutionId) {

        public static Item of(SnapshotItem item) {
            return new Item(item.observationId(), item.state(), item.version(), item.deleted(),
                    item.location(), item.reading(), item.note(), item.lastResolutionId());
        }
    }

    public static SnapshotResponse of(SnapshotHeader header, List<SnapshotItem> items) {
        return new SnapshotResponse(header.snapshotKey(), header.targetTimeUtc(),
                header.globalLatestVersion(), header.createdAtUtc(),
                items.stream().map(Item::of).toList());
    }
}
