package com.example.starter.observation;

/**
 * 按时刻视图的单条结果：给定 observationId 在目标 UTC 时刻的最后版本内容与状态。
 *
 * @param observationId    观测记录唯一标识
 * @param state            目标时刻状态：PRESENT / DELETED / ABSENT
 * @param version          目标时刻最后一个已提交版本号；ABSENT 时为 null
 * @param location         目标时刻版本观测地点；DELETED 与 ABSENT 时为 null
 * @param reading          目标时刻版本观测读数（十进制原文）；DELETED 与 ABSENT 时为 null
 * @param note             目标时刻版本观测备注；DELETED 与 ABSENT 时为 null
 * @param lastResolutionId 目标时刻之前（含该时刻）最近一次冲突解决记录标识；无时为 null
 */
public record AsOfEntry(
        String observationId,
        ObservationState state,
        Integer version,
        String location,
        String reading,
        String note,
        String lastResolutionId) {

    /**
     * 由目标时刻命中的版本快照、墓碑标记与最近解决记录标识构造非 ABSENT 结果。
     */
    public static AsOfEntry of(ObservationSnapshot snapshot, String lastResolutionId) {
        ObservationState state = snapshot.deleted() ? ObservationState.DELETED : ObservationState.PRESENT;
        if (snapshot.deleted()) {
            return new AsOfEntry(snapshot.observationId(), state, snapshot.version(),
                    null, null, null, lastResolutionId);
        }
        return new AsOfEntry(snapshot.observationId(), state, snapshot.version(),
                snapshot.location(), snapshot.reading(), snapshot.note(), lastResolutionId);
    }

    /**
     * 目标时刻记录尚未创建：按 ABSENT 返回，不携带版本与业务字段。
     */
    public static AsOfEntry absent(String observationId) {
        return new AsOfEntry(observationId, ObservationState.ABSENT, null, null, null, null, null);
    }
}
