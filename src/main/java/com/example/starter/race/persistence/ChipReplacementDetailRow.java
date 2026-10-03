package com.example.starter.race.persistence;

/**
 * chip_replacement_detail 表行记录：更换时被重算归属的单条分段读数。
 *
 * @param replacementId  所属更换记录ID
 * @param timingId       分段记录ID
 * @param checkpointCode 检查点代码
 * @param position       检查点顺序（稳定排序用）
 * @param chipBefore     重算前归属芯片；此前未归属为 null
 * @param chipAfter      重算后归属芯片
 */
public record ChipReplacementDetailRow(
        String replacementId,
        String timingId,
        String checkpointCode,
        int position,
        String chipBefore,
        String chipAfter
) {
}