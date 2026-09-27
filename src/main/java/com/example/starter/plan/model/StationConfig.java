package com.example.starter.plan.model;

/**
 * 中间站站台配置（当前版本）。最小停站时长与站台容量修订后版本加一，
 * 发布快照固化发布时版本，历史快照不随后续修订改写。
 *
 * @param id              主键
 * @param stationId       站点 ID，全局唯一
 * @param minDwellMinutes 最小停站时长下限，单位分钟
 * @param platformCapacity 站台容量，单位个：同站到发区间内并发停靠列车数上限
 * @param version         配置版本，首次登记为 1，每次修订加一
 * @param updatedBy       最近修订操作者；null 表示未登记操作者
 * @param createdAt       创建时刻，UTC 毫秒
 * @param updatedAt       最近修订时刻，UTC 毫秒
 */
public record StationConfig(long id, String stationId, int minDwellMinutes, int platformCapacity,
                            int version, String updatedBy, long createdAt, long updatedAt) {
}
