package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param obsType       观测类型：PLAIN 普通观测 / ARC 圆弧观测
 * @param location      观测地点（PLAIN 可编辑字段）；ARC 类型或删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；ARC 类型或删除墓碑版本为 null
 * @param note          观测备注（PLAIN 可编辑字段）；ARC 类型或删除墓碑版本为 null
 * @param startAngle    圆弧起始角，单位千分之一度，0..359999，区间含两端并按模 360000 绕回；仅 ARC 非墓碑版本非空
 * @param arcLength     圆弧正向弧长，单位千分之一度，1..180000；仅 ARC 非墓碑版本非空
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        ObservationType obsType,
        String location,
        String reading,
        String note,
        Integer startAngle,
        Integer arcLength,
        boolean deleted) {

    /**
     * 构造普通观测快照（版本 1 由调用方指定）。
     */
    public static ObservationSnapshot plain(String observationId, int version,
                                            String location, String reading, String note, boolean deleted) {
        return new ObservationSnapshot(observationId, version, ObservationType.PLAIN,
                location, reading, note, null, null, deleted);
    }

    /**
     * 构造圆弧观测快照：起始角与弧长为完整字段组。
     */
    public static ObservationSnapshot arc(String observationId, int version,
                                          Integer startAngle, Integer arcLength, boolean deleted) {
        return new ObservationSnapshot(observationId, version, ObservationType.ARC,
                null, null, null, startAngle, arcLength, deleted);
    }
}
