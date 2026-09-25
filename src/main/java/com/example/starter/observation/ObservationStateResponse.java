package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测当前状态响应：返回当前胜出版本的内容与计时信息。
 *
 * @param observationId   观测标识
 * @param currentVersion  当前胜出版本号
 * @param deviceId        胜出版本的提交设备标识
 * @param deviceLocalTime 胜出版本的设备本地时刻原始值
 * @param correctedAtUtc  胜出版本的矫正后时刻（UTC，ISO-8601）
 * @param mergeSeq        胜出版本的全局合并顺序
 * @param location        观测地点
 * @param reading         观测读数
 * @param note            观测备注
 * @param versionCount    该观测的版本总数
 */
public record ObservationStateResponse(
        String observationId,
        int currentVersion,
        String deviceId,
        LocalDateTime deviceLocalTime,
        Instant correctedAtUtc,
        long mergeSeq,
        String location,
        String reading,
        String note,
        int versionCount) {
}
