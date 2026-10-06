package com.example.starter.race.persistence;

/**
 * pursuit_sample 表行记录。
 *
 * @param raceId     所属赛事ID
 * @param bib        选手参赛号
 * @param seq        采样序号，从1连续递增；隐含起点固定为(0,0)不占序号
 * @param timeMs     从起跑起的时刻（毫秒，正整数）
 * @param distanceMm 累计前进距离（毫米，非负）
 */
public record PursuitSampleRow(
        String raceId,
        String bib,
        int seq,
        long timeMs,
        long distanceMm) {
}
