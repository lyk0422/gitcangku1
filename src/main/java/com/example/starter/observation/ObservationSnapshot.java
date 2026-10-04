package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId    观测记录唯一标识
 * @param version          版本号，从 1 开始
 * @param location         观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading          观测读数，非负十进制字符串，最多三位小数，与限定符组成不可拆分的一组；
 *                         比较按数值（5.0 与 5.000 等价）；删除墓碑版本为 null
 * @param readingQualifier 读数限定符：EXACT/LT/GT；旧普通读数（存储为 NULL）读取时按 EXACT 处理，
 *                         不回写默认值；删除墓碑版本为 null
 * @param note             观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted          是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String readingQualifier,
        String note,
        boolean deleted) {
}