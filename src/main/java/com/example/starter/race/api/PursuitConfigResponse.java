package com.example.starter.race.api;

import java.util.List;

/**
 * 启用追逐赛响应。
 *
 * @param raceId  赛事ID
 * @param version 启用后的赛事版本号
 * @param pairs   已固定的追逐对（按序号升序）
 */
public record PursuitConfigResponse(
        String raceId,
        int version,
        List<PursuitPairResponse> pairs
) {
}
