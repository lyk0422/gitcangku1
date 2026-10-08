package com.example.starter.race.domain;

/**
 * 选手区间计时记录视图：某计时点（检查点或完赛）的累计毫秒闭区间。
 */
public interface IntervalView {

    /** 全局唯一区间记录ID。 */
    String timingId();

    /** 选手参赛号。 */
    String bib();

    /** 计时点代码：检查点代码；完赛区间固定为 {@link IntervalRules#FINISH_CODE}。 */
    String checkpointCode();

    /** 计时点顺序：检查点为其 position，完赛为检查点总数+1。 */
    int position();

    /** 区间下界（毫秒，正整数，闭区间含端点）。 */
    long lowerMs();

    /** 区间上界（毫秒，正整数，闭区间含端点），大于等于下界。 */
    long upperMs();
}
