package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个选手的参赛状态查询结果。
 *
 * @param bib                  参赛号
 * @param version              查询时赛事版本（只读查询不修改版本）
 * @param status               参赛/成绩状态：RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED/DNS/DNF
 * @param rank                 当前名次（并列同名次并跳号）；非 RANKED 为 null；封榜后为固化的最终名次
 * @param finishTimeMs         原始完赛耗时（毫秒）；尚无完赛计时为 null
 * @param lastCheckpointCode   最后通过的检查点代码（已有分段记录中顺序最大者）；无分段记录为 null
 * @param missingCheckpoints   缺失检查点代码，按检查点顺序排列；全部覆盖或无检查点为空列表
 * @param withdrawal           生效中的退赛登记；未退赛为 null（已撤销的退赛见退赛清单）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerStatusResponse(
        String bib,
        int version,
        EntryStatus status,
        Integer rank,
        Long finishTimeMs,
        String lastCheckpointCode,
        List<String> missingCheckpoints,
        WithdrawalResponse withdrawal
) {
}
