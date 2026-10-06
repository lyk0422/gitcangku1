package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId     观测记录唯一标识
 * @param version           版本号，从 1 开始
 * @param location          观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading           观测读数原文（提交时的原始十进制字符串，最多三位小数）；删除墓碑版本为 null
 * @param note              观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted           是否为删除墓碑：true 时业务字段无意义
 * @param dimension         读数量纲：LENGTH / TEMPERATURE；null 表示旧无单位读数（创建时固定，不自动推断）
 * @param readingUnit       该版本读数的原始单位：m / cm / C / K；null 表示无单位（历史版本不改单位）
 * @param readingNormalized 精确规范读数（长度 m、温度 C，十进制原文，比较不舍入）；null 表示无量纲
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String dimension,
        String readingUnit,
        String readingNormalized) {
}
