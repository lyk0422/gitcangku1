package com.example.starter.observation;

import java.math.BigDecimal;

/**
 * 观测点（新类型观测）快照：对应 positioned_observation_current 当前状态或
 * positioned_observation_version 历史版本的一行。坐标与协方差矩阵为一个完整字段组。
 *
 * @param observationId 观测点唯一标识
 * @param version       版本号，从 1 开始
 * @param x             X 坐标，单位米，最多三位小数；删除墓碑版本为 null
 * @param y             Y 坐标，单位米，最多三位小数；删除墓碑版本为 null
 * @param a             协方差矩阵元素 a（X 方向方差），单位平方米，必须为正；删除墓碑版本为 null
 * @param b             协方差矩阵元素 b（XY 协方差），单位平方米，须满足 a*c>b^2；删除墓碑版本为 null
 * @param c             协方差矩阵元素 c（Y 方向方差），单位平方米，必须为正；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record PositionedObservationSnapshot(
        String observationId,
        int version,
        BigDecimal x,
        BigDecimal y,
        BigDecimal a,
        BigDecimal b,
        BigDecimal c,
        boolean deleted) {
}
