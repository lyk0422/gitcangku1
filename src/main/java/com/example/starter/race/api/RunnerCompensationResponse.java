package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个选手的中止补偿明细（只读）。
 *
 * @param bib                  参赛号
 * @param affected             最近一次（及任一）已恢复事件中该选手是否按受影响口径取得补偿
 * @param finishTimeMs         原始完赛耗时（毫秒）；计时缺失为 null
 * @param netFinishTimeMs      净完赛耗时（毫秒）=原始完赛-完赛口径累计补偿；计时缺失为 null
 * @param finishCompensationMs 完赛口径累计补偿毫秒数
 * @param checkpoints          按检查点顺序的逐点补偿明细（缺失检查点净值为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerCompensationResponse(
        String bib,
        boolean affected,
        Long finishTimeMs,
        Long netFinishTimeMs,
        long finishCompensationMs,
        List<CompensationCheckpointResponse> checkpoints
) {
}
