package com.example.starter.race.api;

import com.example.starter.race.domain.LapPoint;

/**
 * 圈次通过记录响应。
 *
 * @param lapPassId     当前记录ID
 * @param bib           参赛号
 * @param lapNo         圈号，从1开始
 * @param point         圈内点：A / B / FINISH
 * @param elapsedMillis 自起跑开始的累计耗时（毫秒，非负）
 * @param createdBy     提交人
 * @param createdAt     提交时间，Unix毫秒时间戳
 */
public record LapPassResponse(
        String lapPassId,
        String bib,
        int lapNo,
        LapPoint point,
        long elapsedMillis,
        String createdBy,
        long createdAt
) {
}