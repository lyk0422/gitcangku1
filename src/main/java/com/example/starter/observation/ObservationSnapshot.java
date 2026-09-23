package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param generation    合并代次：初始 1，每次墓碑恢复加一，删除与普通合并/解决不变
 * @param location      观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note          观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        int generation,
        String location,
        String reading,
        String note,
        boolean deleted) {

    /**
     * 以指定版本与内容、给定代次构造非墓碑快照。
     */
    public static ObservationSnapshot live(String observationId, int version, int generation,
                                          String location, String reading, String note) {
        return new ObservationSnapshot(observationId, version, generation, location, reading, note, false);
    }

    /**
     * 以指定版本与代次构造墓碑快照（业务字段为空）。
     */
    public static ObservationSnapshot tombstone(String observationId, int version, int generation) {
        return new ObservationSnapshot(observationId, version, generation, null, null, null, true);
    }
}
