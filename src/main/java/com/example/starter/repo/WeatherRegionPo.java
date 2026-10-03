package com.example.starter.repo;

/**
 * 气象区域持久化记录（非退化轴对齐闭矩形，单位米）。
 *
 * @param regionId    气象区域唯一标识
 * @param xMin        左边界（含），米
 * @param yMin        下边界（含），米
 * @param xMax        右边界（含），米
 * @param yMax        上边界（含），米
 * @param dutyVersion 本次配置生效时的气象值班版本
 * @param createdAt   本次配置时间（epoch 毫秒，UTC）
 */
public record WeatherRegionPo(String regionId, int xMin, int yMin, int xMax, int yMax,
                              long dutyVersion, long createdAt) {
}