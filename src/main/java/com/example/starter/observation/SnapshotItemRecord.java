package com.example.starter.observation;

/**
 * 冻结快照逐条固化内容：对应 observation_snapshot_item 表的一行，写入后不可变。
 *
 * @param snapshotKey      所属冻结快照标识
 * @param ordinal          条目序号，从 0 开始按 observationId 升序
 * @param observationId    观测记录唯一标识
 * @param state            目标时刻状态：PRESENT / DELETED / ABSENT
 * @param version          目标时刻最后一个已提交版本号；ABSENT 时为 null
 * @param location         目标时刻版本观测地点；DELETED 与 ABSENT 时为 null
 * @param reading          目标时刻版本观测读数（十进制原文）；DELETED 与 ABSENT 时为 null
 * @param note             目标时刻版本观测备注；DELETED 与 ABSENT 时为 null
 * @param lastResolutionId 目标时刻之前（含该时刻）最近一次冲突解决记录标识；无时为 null
 */
public record SnapshotItemRecord(
        String snapshotKey,
        int ordinal,
        String observationId,
        ObservationState state,
        Integer version,
        String location,
        String reading,
        String note,
        String lastResolutionId) {
}
