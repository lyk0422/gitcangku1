package com.example.starter.observation.geo;

/**
 * 二维坐标观测记录快照：对应 geo_observation_current 当前状态或 geo_observation_version 历史版本的一行。
 *
 * <p>坐标 (x, y) 与协方差矩阵 (a, b, c) 在三方合并中视为一个完整字段组，不逐字段拼装。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param x             观测点 X 坐标，单位米，十进制字符串，最多三位小数；删除墓碑版本为 null
 * @param y             观测点 Y 坐标，单位米，十进制字符串，最多三位小数；删除墓碑版本为 null
 * @param a             协方差矩阵元素 a（X 方差），单位平方米，最多三位小数，必须为正；墓碑版本为 null
 * @param b             协方差矩阵元素 b（XY 协方差），单位平方米，最多三位小数；墓碑版本为 null
 * @param c             协方差矩阵元素 c（Y 方差），单位平方米，最多三位小数，必须为正；墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record GeoObservationSnapshot(
        String observationId,
        int version,
        String x,
        String y,
        String a,
        String b,
        String c,
        boolean deleted) {
}
