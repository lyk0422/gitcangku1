package com.example.starter.observation;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId   观测记录唯一标识
 * @param version         版本号，从 1 开始
 * @param location        观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading         观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note            观测备注（可编辑字段）；删除墓碑版本为 null
 * @param individualCount 个体计数，非负整数；与 sampledArea 作为一组同时存在或同时为 null；墓碑版本为 null
 * @param sampledArea     采样面积，正整数，单位平方米；与 individualCount 成组；墓碑版本为 null
 * @param deleted         是否为删除墓碑：true 时业务字段无意义
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        Integer individualCount,
        Integer sampledArea,
        boolean deleted) {
}
