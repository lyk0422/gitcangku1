package com.example.starter.race.persistence;

/**
 * trajectory_sample 表行记录：轨迹当前版本的采样点。
 *
 * @param raceId     所属赛事ID
 * @param bib        选手参赛号
 * @param seq        采样序号，从0开始；seq=0固定为(0,0)
 * @param elapsedMs  从起跑起的累计毫秒（非负整数，同一人严格递增）
 * @param progressMm 累计前进毫米（非负整数，同一人单调不减）
 */
public record TrajectorySampleRow(
        String raceId,
        String bib,
        int seq,
        long elapsedMs,
        long progressMm) {
}
