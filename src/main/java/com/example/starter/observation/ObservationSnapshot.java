package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId 观测记录唯一标识
 * @param version       代次（generation），从 1 开始，每次成功内容变更加一
 * @param location      观测地点（可编辑业务字段）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note          观测备注（可编辑业务字段）；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 * @param siteKey       站点标识，重复观测簇归并的匹配维度之一；墓碑版本仍保留
 * @param observationType 观测类型，重复观测簇归并的匹配维度之一；墓碑版本仍保留
 * @param observedAt    观测发生时刻（UTC）；墓碑版本仍保留
 * @param deviceId      采集设备标识，归并预览时随代次一起冻结；可为 null
 * @param status        归并状态：ACTIVE 活跃未归并 / MERGED 已归并不再接受变更
 * @param origin        记录来源：RAW 原始上报 / CANONICAL 簇归并产生的主记录
 * @param mergedInto    归并目标主记录键；status=MERGED 时非空，否则为 null
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String siteKey,
        String observationType,
        Instant observedAt,
        String deviceId,
        RecordStatus status,
        RecordOrigin origin,
        String mergedInto) {
}
