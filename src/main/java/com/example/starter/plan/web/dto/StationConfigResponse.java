package com.example.starter.plan.web.dto;

/**
 * 站台配置当前版本视图。
 *
 * @param stationId         站点业务 ID
 * @param version           配置版本，从 1 开始，修订加一
 * @param minDwellMinutes   当前要求的最小停站分钟数
 * @param platformCapacity  当前站台容量
 * @param createdAt         配置登记时刻，UTC 毫秒
 * @param updatedAt         最近修订时刻，UTC 毫秒
 */
public record StationConfigResponse(String stationId, int version, int minDwellMinutes,
                                    int platformCapacity, long createdAt, long updatedAt) {
}
