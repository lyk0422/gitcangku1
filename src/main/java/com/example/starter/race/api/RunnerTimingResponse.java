package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个选手分段查询响应：按检查点顺序稳定返回每个检查点的通过情况。
 *
 * @param raceId          赛事ID
 * @param bib             参赛号
 * @param version         查询时的赛事版本（只读查询不修改版本）
 * @param finishTimeMs    该选手原始完赛耗时（毫秒）；尚无完赛计时为 null
 * @param netFinishTimeMs 该选手净完赛耗时（毫秒）；尚无完赛计时为 null
 * @param checkpoints     按 position 升序的分段明细；未配置检查点的赛事为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerTimingResponse(
        String raceId,
        String bib,
        int version,
        Long finishTimeMs,
        Long netFinishTimeMs,
        List<CheckpointPassResponse> checkpoints
) {

    /** 无中止事件场景的兼容构造器：净完赛耗时等于原始完赛耗时。 */
    public RunnerTimingResponse(
            String raceId,
            String bib,
            int version,
            Long finishTimeMs,
            List<CheckpointPassResponse> checkpoints) {
        this(raceId, bib, version, finishTimeMs, finishTimeMs, checkpoints);
    }
}
