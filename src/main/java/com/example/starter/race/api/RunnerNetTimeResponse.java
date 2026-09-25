package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个参赛者净计时查询响应。
 *
 * @param bib           参赛号
 * @param version       查询时赛事版本（只读不修改版本）
 * @param status        成绩状态（RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED/INVALID_WAVE）
 * @param finishTimeMs  原始枪声完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs     生效加时合计毫秒数
 * @param totalTimeMs   枪声总耗时=原始完赛耗时+生效加时（毫秒）；无有效枪声计时为 null
 * @param waveKey       所属波次唯一键；无波次参赛者为 null
 * @param waveStartMs   所属波次 UTC 起跑时刻（Unix 毫秒时间戳）；无波次为 null
 * @param baseStartMs   赛事基准起跑时刻（Unix 毫秒时间戳）；无波次或基准缺失为 null
 * @param netTimeMs     最终净计时（毫秒）：有波次=枪声总耗时-(波次起跑-基准起跑)，无波次=枪声总耗时；
 *                      负净计时或其它非 RANKED 情况为 null
 * @param invalidReason 非排名原因：INVALID_WAVE-净计时为负；正常排名或其它状态为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerNetTimeResponse(
        String bib,
        int version,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        String waveKey,
        Long waveStartMs,
        Long baseStartMs,
        Long netTimeMs,
        String invalidReason
) {
}
