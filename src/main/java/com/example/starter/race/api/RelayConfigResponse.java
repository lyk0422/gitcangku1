package com.example.starter.race.api;

import java.util.List;

/**
 * 接力配置响应。
 *
 * @param raceId          赛事ID
 * @param version         配置成功后的赛事版本号
 * @param legCount        棒次数
 * @param exchangeLimitMs 交接区用时上限（毫秒）
 * @param teams           已登记队伍标识，按配置顺序
 */
public record RelayConfigResponse(
        String raceId,
        int version,
        int legCount,
        long exchangeLimitMs,
        List<String> teams
) {
}
