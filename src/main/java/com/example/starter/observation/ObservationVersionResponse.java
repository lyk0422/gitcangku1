package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测单版本响应：用于版本列表查询。
 *
 * @param version         版本号
 * @param deviceId        提交设备标识
 * @param deviceLocalTime 设备本地时刻原始值
 * @param correctedAtUtc  矫正后时刻（UTC，ISO-8601）
 * @param mergeSeq        全局合并顺序
 * @param location        观测地点
 * @param reading         观测读数
 * @param note            观测备注
 * @param current         是否为当前胜出版本
 */
public record ObservationVersionResponse(
        int version,
        String deviceId,
        LocalDateTime deviceLocalTime,
        Instant correctedAtUtc,
        long mergeSeq,
        String location,
        String reading,
        String note,
        boolean current) {
}
