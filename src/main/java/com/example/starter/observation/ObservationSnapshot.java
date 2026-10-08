package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * <p>FIELD 类型使用 location/reading/note 三个可编辑字段；SECTION 类型使用
 * sectionNo/lateralMm/elevationMm 三个字段组成的完整版本组（合并时整体判定，不可拆分拼接）。
 * 不属于当前类型的字段一律为 null。
 *
 * @param observationId   观测记录唯一标识
 * @param version         版本号，从 1 开始
 * @param observationType 观测类型：FIELD / SECTION
 * @param location        观测地点（FIELD 可编辑字段）；删除墓碑版本为 null
 * @param reading         观测读数，十进制字符串，最多三位小数，比较按数值（FIELD）；删除墓碑版本为 null
 * @param note            观测备注（FIELD 可编辑字段）；删除墓碑版本为 null
 * @param sectionNo       断面号（SECTION 版本组字段，文本原样比较）；删除墓碑版本为 null
 * @param lateralMm       横向坐标，整数毫米（SECTION 版本组字段）；删除墓碑版本为 null
 * @param elevationMm     高程，整数毫米（SECTION 版本组字段）；删除墓碑版本为 null
 * @param deleted         是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        ObservationType observationType,
        String location,
        String reading,
        String note,
        String sectionNo,
        Integer lateralMm,
        Integer elevationMm,
        boolean deleted) {

    /**
     * 构造 FIELD 类型快照。
     */
    public static ObservationSnapshot field(String observationId, int version,
                                            String location, String reading, String note, boolean deleted) {
        return new ObservationSnapshot(observationId, version, ObservationType.FIELD,
                location, reading, note, null, null, null, deleted);
    }

    /**
     * 构造 SECTION 类型快照。
     */
    public static ObservationSnapshot section(String observationId, int version,
                                              String sectionNo, Integer lateralMm, Integer elevationMm,
                                              boolean deleted) {
        return new ObservationSnapshot(observationId, version, ObservationType.SECTION,
                null, null, null, sectionNo, lateralMm, elevationMm, deleted);
    }

    /**
     * 构造删除墓碑快照（保留原类型，业务字段清空）。
     */
    public static ObservationSnapshot tombstone(ObservationSnapshot current, int newVersion) {
        return new ObservationSnapshot(current.observationId(), newVersion, current.observationType(),
                null, null, null, null, null, null, true);
    }
}
