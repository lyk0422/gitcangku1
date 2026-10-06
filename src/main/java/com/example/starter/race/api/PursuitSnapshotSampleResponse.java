package com.example.starter.race.api;

/**
 * 封榜冻结的轨迹采样点。
 *
 * @param bib        选手参赛号
 * @param seq        采样序号，从1连续递增；隐含起点固定为(0,0)不占序号
 * @param timeMs     从起跑起的时刻（毫秒）
 * @param distanceMm 累计前进距离（毫米）
 */
public record PursuitSnapshotSampleResponse(
        String bib,
        int seq,
        long timeMs,
        long distanceMm
) {
}
