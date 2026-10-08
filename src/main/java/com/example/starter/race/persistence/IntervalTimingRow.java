package com.example.starter.race.persistence;

import com.example.starter.race.domain.IntervalView;

/**
 * interval_timing 表行记录（区间计时模式下某计时点的累计毫秒闭区间）。
 *
 * @param timingId       全局唯一区间记录ID（第二层幂等键）
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 计时点代码；完赛区间固定为 FINISH
 * @param position       计时点顺序：检查点为其 position，完赛为检查点总数+1
 * @param lowerMs        区间下界（毫秒，正整数，闭区间含端点）
 * @param upperMs        区间上界（毫秒，正整数，闭区间含端点）
 * @param operator       最近一次写入的操作者标识
 * @param createdAt      记录提交时间，Unix毫秒时间戳
 * @param updatedAt      最近一次区间修订时间，Unix毫秒时间戳
 */
public record IntervalTimingRow(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int position,
        long lowerMs,
        long upperMs,
        String operator,
        long createdAt,
        long updatedAt
) implements IntervalView {
}
