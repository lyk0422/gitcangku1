package com.example.starter.prediction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 卫星过境预测的一个版本。
 *
 * @param id 主键
 * @param predictionKey 预测业务键
 * @param version 版本号
 * @param satelliteId 卫星标识
 * @param windowStartUtc 可见窗口起点，UTC
 * @param windowEndUtc 可见窗口终点，UTC
 * @param minElevationDeg 窗口内最低仰角，单位度
 * @param plannedDataMb 计划数据量，单位 MB
 * @param status CURRENT=生效，SUPERSEDED=已取代
 * @param createdAtUtc 创建时间，UTC
 */
public record PassPrediction(
        long id,
        String predictionKey,
        int version,
        String satelliteId,
        Instant windowStartUtc,
        Instant windowEndUtc,
        BigDecimal minElevationDeg,
        long plannedDataMb,
        String status,
        Instant createdAtUtc) {

    /** 生效中。 */
    public static final String STATUS_CURRENT = "CURRENT";
    /** 已被新版本取代。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
}