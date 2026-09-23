package com.example.starter.race.persistence;

/**
 * penalty_appeal_segment 表行记录：受理时冻结的选手单检查点判定。
 *
 * @param appealKey      所属申诉键
 * @param bib            选手参赛号
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序，从1递增
 * @param elapsedMillis  冻结的累计耗时（毫秒）；缺失检查点为 null
 * @param timingId       冻结的分段记录ID；缺失检查点为 null
 */
public record PenaltyAppealSegmentRow(
        String appealKey,
        String bib,
        String checkpointCode,
        int position,
        Long elapsedMillis,
        String timingId
) {
}
