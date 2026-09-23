package com.example.starter.observation;

import java.time.Instant;

/**
 * 簇成员预览冻结项：提交预览时记录的代次、设备、观测时间与业务字段值。
 *
 * @param recordKey       成员观测记录键
 * @param generation      冻结时的代次；归并提交必须原样回传
 * @param siteKey         站点标识
 * @param observationType 观测类型
 * @param deviceId        采集设备标识，可为空
 * @param observedAt      观测发生时刻（UTC）
 * @param location        冻结的观测地点
 * @param reading         冻结的观测读数（十进制原文）
 * @param note            冻结的观测备注
 */
public record ClusterMemberPreview(
        String recordKey,
        int generation,
        String siteKey,
        String observationType,
        String deviceId,
        Instant observedAt,
        String location,
        String reading,
        String note) {
}
