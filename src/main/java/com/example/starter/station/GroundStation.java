package com.example.starter.station;

import java.math.BigDecimal;

/**
 * 地面站与天线能力。
 *
 * @param id 主键
 * @param code 站点代码
 * @param antennaBand 天线能力频段
 * @param minElevationDeg 可接受最低仰角，单位度
 * @param capacityMbPerMin 单天线每分钟容量，单位 MB/分钟
 */
public record GroundStation(
        long id,
        String code,
        String antennaBand,
        BigDecimal minElevationDeg,
        int capacityMbPerMin) {
}