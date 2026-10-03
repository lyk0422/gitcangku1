package com.example.starter.model;

import java.math.BigDecimal;

/**
 * 地面接收站。
 *
 * @param id 主键
 * @param code 站点编码，全局唯一
 * @param name 站点名称
 * @param antennaCapability 天线能力（频段）
 * @param minElevationDeg 可接收的最低仰角门槛（度）
 * @param capacityBytesPerMin 天线每分钟接收容量（字节/分钟）
 */
public record GroundStation(
        long id,
        String code,
        String name,
        String antennaCapability,
        BigDecimal minElevationDeg,
        long capacityBytesPerMin) {
}