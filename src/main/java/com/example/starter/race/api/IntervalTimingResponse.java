package com.example.starter.race.api;

/**
 * 区间计时记录响应。
 *
 * @param timingId       全局唯一区间记录ID
 * @param bib            选手参赛号
 * @param checkpointCode 计时点代码；完赛区间固定为 FINISH
 * @param position       计时点顺序，检查点从1递增，完赛最后
 * @param lowerMs        区间下界（毫秒，闭区间含端点）
 * @param upperMs        区间上界（毫秒，闭区间含端点）
 * @param operator       最近一次写入的操作者标识
 * @param createdAt      提交时间，Unix毫秒时间戳
 * @param updatedAt      最近一次修订时间，Unix毫秒时间戳
 */
public record IntervalTimingResponse(
        String timingId,
        String bib,
        String checkpointCode,
        int position,
        long lowerMs,
        long upperMs,
        String operator,
        long createdAt,
        long updatedAt
) {
}
