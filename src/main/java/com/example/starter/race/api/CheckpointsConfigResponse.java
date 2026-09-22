package com.example.starter.race.api;

import java.util.List;

/**
 * 检查点配置响应。
 *
 * @param raceId       赛事ID
 * @param version      配置成功后的赛事版本号
 * @param checkpoints  按 position 升序的检查点列表
 */
public record CheckpointsConfigResponse(
        String raceId,
        int version,
        List<CheckpointResponse> checkpoints
) {
}
