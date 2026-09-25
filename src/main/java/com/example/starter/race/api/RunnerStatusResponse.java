package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 选手参赛状态查询响应。
 *
 * @param raceId              赛事ID
 * @param bib                 参赛号
 * @param status              当前参赛状态（RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED/DNS/DNF）
 * @param finishTimeMs        原始完赛耗时毫秒；计时缺失为 null
 * @param lastCheckpointCode  最后通过的检查点代码；无任何分段记录为 null
 * @param missingCheckpoints  缺失检查点代码，按检查点顺序排列；无缺失为空列表
 * @param withdrawal          最近一次退赛登记（含已撤销）；从未退赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerStatusResponse(
        String raceId,
        String bib,
        EntryStatus status,
        Long finishTimeMs,
        String lastCheckpointCode,
        List<String> missingCheckpoints,
        WithdrawalResponse withdrawal
) {
}
