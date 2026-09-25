package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个参赛者的净计时查询响应。
 *
 * @param bib          参赛号
 * @param version      榜单对应的赛事版本号
 * @param status       成绩状态（RANKED / INVALID_WAVE / UNTIMED / MISSING_CHECKPOINT / DISQUALIFIED）
 * @param waveKey      所属波次唯一键；不属于任何波次为 null
 * @param waveStartAt  所属波次UTC起跑时刻，Unix毫秒时间戳；无波次为 null
 * @param baseStartAt  赛事基准起跑时刻，Unix毫秒UTC时间戳
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param gunTimeMs    最终枪声计时毫秒=原始完赛耗时+生效加时；无枪声计时为 null
 * @param netTimeMs    最终净计时毫秒；INVALID_WAVE（净计时为负）或无枪声计时为 null
 * @param invalidReason 无效原因：INVALID_WAVE-净计时为负；其余为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerNetTimeResponse(
        String bib,
        int version,
        EntryStatus status,
        String waveKey,
        Long waveStartAt,
        long baseStartAt,
        Long finishTimeMs,
        Long gunTimeMs,
        Long netTimeMs,
        String invalidReason
) {
}
