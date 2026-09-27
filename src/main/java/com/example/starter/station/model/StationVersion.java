package com.example.starter.station.model;

/**
 * 站台配置不可变版本历史中的一个版本，追加后不得改写。
 *
 * @param stationId       站点业务键
 * @param version         配置版本，从 1 开始
 * @param minDwellMinutes 该版本最小停站分钟数
 * @param platformCapacity 该版本站台容量
 * @param operatorId      产生该版本的操作者
 * @param createdAt       该版本提交时刻，UTC 毫秒
 */
public record StationVersion(String stationId, int version, int minDwellMinutes,
                             int platformCapacity, String operatorId, long createdAt) {
}
