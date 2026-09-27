package com.example.starter.race.persistence;

import com.example.starter.race.domain.FinishTimingView;

/**
 * finish_timing_record 表行记录（不可变终点计时事件证据）。
 *
 * @param finishTimingId 终点计时事件ID，全局唯一
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param occurredAt     终点计时事件发生的绝对UTC毫秒时间戳
 * @param finishTimeMs   该次终点计时的原始完赛耗时（毫秒）
 * @param createdAt      记录提交的绝对UTC毫秒时间戳
 */
public record FinishTimingRecordRow(
        String finishTimingId,
        String raceId,
        String bib,
        long occurredAt,
        long finishTimeMs,
        long createdAt
) implements FinishTimingView {
}
