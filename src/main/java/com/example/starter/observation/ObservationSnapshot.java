package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * <p>历史版本表不携带许可采样列，因此从历史版本构造时采样字段为 null。
 *
 * @param observationId      观测记录唯一标识
 * @param version            版本号，从 1 开始
 * @param location           观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading            观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note               观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted            是否为删除墓碑：true 时业务字段无意义
 * @param deviceId           采样设备标识；旧的直接创建路径及历史版本行为 null
 * @param gridId             采集网格单元编号；旧路径为 null
 * @param observedAtUtc      采集时刻（UTC，离线补传为实际采集时刻）；旧路径为 null
 * @param permitId           接收时命中的采样许可标识；旧路径为 null
 * @param permitVersion      接收时命中许可的 permitVersion 快照；旧路径为 null
 * @param observationStatus  观测业务状态（RECEIVED/MERGED/PERMIT_REVIEW）；旧路径为 null
 * @param reviewSnapshotJson 复核快照 JSON 原文：许可撤销时写入；null 表示未进入复核
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String deviceId,
        Integer gridId,
        Instant observedAtUtc,
        String permitId,
        String permitVersion,
        ObservationStatus observationStatus,
        String reviewSnapshotJson) {

    /**
     * 历史版本与旧的直接创建路径使用的精简构造：采样字段一律为 null。
     */
    public ObservationSnapshot(String observationId, int version, String location, String reading,
                               String note, boolean deleted) {
        this(observationId, version, location, reading, note, deleted,
                null, null, null, null, null, null, null);
    }
}
