package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测记录快照：对应 observation_current 当前状态或 observation_version 历史版本的一行。
 *
 * @param observationId  观测记录唯一标识
 * @param version        版本号，从 1 开始
 * @param location       观测地点（可编辑字段）；删除墓碑版本为 null
 * @param reading        观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param note           观测备注（可编辑字段）；删除墓碑版本为 null
 * @param deleted        是否为删除墓碑：true 时业务字段无意义
 * @param deviceId       采集设备标识（提交时绑定）；删除墓碑版本为 null
 * @param gridX          采集网格 X 坐标（提交时绑定）；删除墓碑版本为 null
 * @param gridY          采集网格 Y 坐标（提交时绑定）；删除墓碑版本为 null
 * @param collectedAtUtc 采集时刻（UTC）；删除墓碑版本为 null
 * @param permitId       提交时命中的采样许可标识；删除墓碑版本为 null
 * @param permitVersion  提交时命中的许可版本；删除墓碑版本为 null
 * @param reviewStatus   复核标记：NONE / PERMIT_REVIEW；仅当前状态有意义，历史版本为 null
 */
public record ObservationSnapshot(
        String observationId,
        int version,
        String location,
        String reading,
        String note,
        boolean deleted,
        String deviceId,
        Integer gridX,
        Integer gridY,
        Instant collectedAtUtc,
        String permitId,
        Integer permitVersion,
        String reviewStatus) {
}
