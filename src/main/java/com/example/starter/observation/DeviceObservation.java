package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测提交（版本）：对应 device_observation 表的一行。
 * 原始本地时刻不可改写；矫正后时刻与合并顺序仅由系统在提交或偏移重建时维护。
 *
 * @param observationId   观测标识；同一观测的多次提交构成其版本序列
 * @param version         版本号，按提交到达顺序从 1 开始单调递增
 * @param deviceId        提交设备标识
 * @param deviceLocalTime 设备本地时刻原始值（无区字面量，不可改写）
 * @param correctedAtUtc  矫正后时刻（UTC）= 设备本地时刻 + 命中偏移秒数
 * @param location        观测地点
 * @param reading         观测读数（十进制字符串，最多三位小数）
 * @param note            观测备注
 * @param mergeSeq        全局合并顺序：按（矫正后时刻, 设备标识, 观测标识, 版本）升序排位
 */
public record DeviceObservation(
        String observationId,
        int version,
        String deviceId,
        LocalDateTime deviceLocalTime,
        Instant correctedAtUtc,
        String location,
        String reading,
        String note,
        long mergeSeq) {

    /**
     * 返回替换矫正后时刻与合并顺序后的副本；其余字段保持不变。
     */
    public DeviceObservation withTiming(Instant newCorrectedAtUtc, long newMergeSeq) {
        return new DeviceObservation(observationId, version, deviceId, deviceLocalTime,
                newCorrectedAtUtc, location, reading, note, newMergeSeq);
    }
}
