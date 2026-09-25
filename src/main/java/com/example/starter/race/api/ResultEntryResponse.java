package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜中的单个条目。
 *
 * @param bib                    参赛号
 * @param rank                   名次（并列同名次并跳号）；非 RANKED 为 null
 * @param status                RANKED / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED / INVALID_WAVE
 * @param finishTimeMs          原始枪声完赛耗时毫秒；计时缺失为 null
 * @param penaltyMs             生效加时合计毫秒数
 * @param totalTimeMs           枪声总耗时毫秒（原始完赛+生效加时）；未排名为 null
 * @param waveKey               所属波次唯一键；无波次参赛者为 null
 * @param waveStartMs           所属波次 UTC 起跑时刻（Unix 毫秒时间戳）；无波次为 null
 * @param baseStartMs           赛事基准起跑时刻（Unix 毫秒时间戳）；无波次或基准缺失为 null
 * @param netTimeMs             最终净计时毫秒（按净计时排名）；非 RANKED 为 null
 * @param invalidReason         非排名原因：INVALID_WAVE-净计时为负；正常排名或其它状态为 null
 * @param checkpointCount       赛事检查点总数；未配置检查点为 0
 * @param coveredCheckpointCount 该选手已覆盖检查点数量
 * @param missingCheckpoints    缺失检查点代码，按检查点顺序排列；无缺失为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultEntryResponse(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        String waveKey,
        Long waveStartMs,
        Long baseStartMs,
        Long netTimeMs,
        String invalidReason,
        int checkpointCount,
        int coveredCheckpointCount,
        List<String> missingCheckpoints
) {
}
