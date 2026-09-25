package com.example.starter.race.api;

/**
 * 赛事器材检录配置响应。
 *
 * @param raceId       赛事ID
 * @param version      配置成功后的赛事版本号
 * @param mandatory    是否强制检录
 * @param validMinutes 检录 PASS 有效分钟数（1~1440）
 * @param createdAt    首次配置时间，Unix毫秒时间戳
 * @param updatedAt    最近配置时间，Unix毫秒时间戳
 */
public record InspectionConfigResponse(
        String raceId,
        int version,
        boolean mandatory,
        int validMinutes,
        long createdAt,
        long updatedAt
) {
}
