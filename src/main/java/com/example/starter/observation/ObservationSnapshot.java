package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param location      观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note          观测备注（可编辑字段）；删除墓碑版本为 null
 * @param dx            位移 x 分量（米，十进制字符串，最多三位小数）；与 dy、w 组成可选完整字段组，
 *                      无位移组或删除墓碑版本为 null
 * @param dy            位移 y 分量（米）；无位移组或删除墓碑版本为 null
 * @param w             边长（米，正数）；无位移组或删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        String dx,
        String dy,
        String w,
        boolean deleted) {
}
