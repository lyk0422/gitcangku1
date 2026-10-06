package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param location      观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading       规范读数：带量纲记录为精确规范值（长度 m/温度 C，不舍入），旧无单位记录为原始十进制字符串；删除墓碑版本为 null
 * @param readingUnit   该版本读数的原始上报单位（m/cm/C/K）；旧无单位读数为 null
 * @param dimension     量纲：LENGTH / TEMPERATURE，创建时固定；旧无单位读数为 null（不自动推断）
 * @param note          观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String readingUnit,
        String dimension,
        String note,
        boolean deleted) {

    /**
     * 旧无单位读数的快照构造：量纲与单位均为 null。
     */
    public ObservationSnapshot(String observationId, int version, String location,
                               String reading, String note, boolean deleted) {
        this(observationId, version, location, reading, null, null, note, deleted);
    }
}
