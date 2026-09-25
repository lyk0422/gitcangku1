package com.example.starter.observation;

import java.time.Instant;

/**
 * 基准重算记录：重算改变簇成员或胜出记录时写入，固化新旧簇与参数版本，不可变。
 *
 * @param recalcId        基准重算记录唯一标识
 * @param deviceId        被重算的设备唯一标识
 * @param oldFrameVersion 重算前设备基准参数版本；首次登记为 null
 * @param newFrameVersion 重算后设备基准参数版本
 * @param oldClusters     重算前未人工裁决簇快照（JSON 数组原文）
 * @param newClusters     重算后未人工裁决簇快照（JSON 数组原文）
 * @param recalcedAtUtc   重算完成时刻（UTC）
 */
public record FrameRecalcRecord(
        String recalcId,
        String deviceId,
        String oldFrameVersion,
        String newFrameVersion,
        String oldClusters,
        String newClusters,
        Instant recalcedAtUtc) {
}
