package com.example.starter.plan.model;

/**
 * 中间站配置当前版本。版本从 1 开始，修订一次加一；发布时按当时版本固化进快照。
 *
 * @param id              主键
 * @param stationId       站点业务 ID，全局唯一
 * @param version         配置版本，从 1 开始
 * @param minDwellMinutes 最小停站分钟数（非负整数）
 * @param platformCapacity 站台容量（正整数），同一时刻允许同时占用站台的列车数
 * @param createdAt       配置登记时刻，UTC 毫秒
 * @param updatedAt       最近修订时刻，UTC 毫秒
 */
public record StationConfig(long id, String stationId, int version, int minDwellMinutes,
                            int platformCapacity, long createdAt, long updatedAt) {
}
