package com.example.starter.observation;

/**
 * 冻结快照逐条固化内容：对应 observation_snapshot_item 表的一行，随快照头同事务原子写入，不可变。
 *
 * @param snapshotKey      所属快照标识
 * @param ordinal          条目顺序（按 observationId 升序，从 1 开始）
 * @param observationId    观测记录唯一标识
 * @param state            目标时刻状态：ACTIVE 正常版本 / TOMBSTONE 删除墓碑 / ABSENT 目标时刻后才创建
 * @param version          目标时刻该记录最后一个版本号；ABSENT 时为 null
 * @param deleted          是否为删除墓碑：true 时业务字段为 null
 * @param location         目标时刻版本观测地点快照；墓碑与 ABSENT 为 null
 * @param reading          目标时刻版本观测读数快照原文；墓碑与 ABSENT 为 null
 * @param note             目标时刻版本观测备注快照；墓碑与 ABSENT 为 null
 * @param lastResolutionId 目标时刻之前（含该时刻）最近一次冲突解决记录标识；无则为 null
 */
public record SnapshotItem(
        String snapshotKey,
        int ordinal,
        String observationId,
        String state,
        Integer version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String lastResolutionId) {
}
