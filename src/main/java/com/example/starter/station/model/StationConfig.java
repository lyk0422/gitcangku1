package com.example.starter.station.model;

/**
 * 站台（中间站）当前配置。
 *
 * @param stationId       站点业务键
 * @param minDwellMinutes 当前最小停站分钟数（&ge;0）
 * @param platformCapacity 当前站台容量（&ge;1）
 * @param version         当前配置版本，从 1 开始，每次修订加一
 * @param operatorId      最近修订操作者
 * @param createdAt       首次创建时刻，UTC 毫秒
 * @param updatedAt       最近修订时刻，UTC 毫秒
 */
public record StationConfig(String stationId, int minDwellMinutes, int platformCapacity,
                            int version, String operatorId, long createdAt, long updatedAt) {
}
