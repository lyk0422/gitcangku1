package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卫星过境预测（版本化）。
 *
 * @param id 主键
 * @param satelliteCode 卫星编号
 * @param version 预测版本号，同一卫星从 1 递增
 * @param windowStartUtc 可见窗口开始（UTC）
 * @param windowEndUtc 可见窗口结束（UTC）
 * @param minElevationDeg 窗口内最低仰角（度）
 * @param plannedBytes 计划下传数据量（字节）
 * @param superseded 是否已被更新版本取代
 * @param createdAtUtc 记录创建时间（UTC）
 */
public record PassPrediction(
        long id,
        String satelliteCode,
        int version,
        Instant windowStartUtc,
        Instant windowEndUtc,
        BigDecimal minElevationDeg,
        long plannedBytes,
        boolean superseded,
        Instant createdAtUtc) {
}