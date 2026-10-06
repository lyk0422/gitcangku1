package com.example.starter.excursion;

/**
 * 越限观测快照：对应 excursion_current 当前状态或 excursion_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param stationId     站号（可编辑字段）；删除墓碑版本为 null
 * @param minuteUtc     UTC 整分钟时刻，ISO-8601 原文（YYYY-MM-DDThh:mm:00Z，可编辑字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record ExcursionSnapshot(
        String observationId,
        int version,
        String stationId,
        String minuteUtc,
        String reading,
        boolean deleted) {
}
