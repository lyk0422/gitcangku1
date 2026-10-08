package com.example.starter.race.api;

import java.util.List;

/**
 * 阶段配置响应。
 *
 * @param raceId  赛事ID
 * @param version 配置后的赛事版本号
 * @param stages  已固定的阶段及其有序检查点
 */
public record StagesConfigResponse(
        String raceId,
        int version,
        List<StageConfig> stages
) {

    /**
     * 单个阶段配置。
     *
     * @param stageNo     阶段序号
     * @param checkpoints 阶段内检查点，按 position 升序
     */
    public record StageConfig(int stageNo, List<CheckpointResponse> checkpoints) {
    }
}
