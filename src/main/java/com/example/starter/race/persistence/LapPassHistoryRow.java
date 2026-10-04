package com.example.starter.race.persistence;

import com.example.starter.race.domain.LapPoint;

/**
 * lap_pass_history 表行记录：被更正替换的原始读数，更正只追加历史，不覆盖原读数。
 *
 * @param historyId    被替换的原记录ID（即原 lap_pass_id），全局唯一
 * @param replacedBy   替换后的当前记录ID
 * @param raceId       所属赛事ID
 * @param bib          选手参赛号
 * @param lapNo        圈号，从1开始
 * @param point        圈内点：A/B/FINISH
 * @param elapsedMillis 被替换前的原始读数（毫秒，非负）
 * @param createdBy    原记录提交人
 * @param createdAt    原记录提交时间，Unix毫秒时间戳
 * @param correctedBy  更正操作人
 * @param correctedAt  更正时间，Unix毫秒时间戳
 */
public record LapPassHistoryRow(
        String historyId,
        String replacedBy,
        String raceId,
        String bib,
        int lapNo,
        LapPoint point,
        long elapsedMillis,
        String createdBy,
        long createdAt,
        String correctedBy,
        long correctedAt
) {
}