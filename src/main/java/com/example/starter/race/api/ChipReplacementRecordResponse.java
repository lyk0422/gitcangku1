package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 芯片更换历史记录（含重算前后归属证据）。
 *
 * @param replacementId     更换记录ID
 * @param bib               参赛号
 * @param oldChip           旧芯片ID
 * @param newChip           新芯片ID
 * @param effectiveAt       生效时刻，Unix毫秒时间戳（UTC）
 * @param referee           确认裁判
 * @param createdAt         登记时间，Unix毫秒时间戳
 * @param attributionBefore 重算前归属明细，按检查点顺序
 * @param attributionAfter  重算后归属明细，按检查点顺序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipReplacementRecordResponse(
        String replacementId,
        String bib,
        String oldChip,
        String newChip,
        long effectiveAt,
        String referee,
        long createdAt,
        List<ChipAttributionEntry> attributionBefore,
        List<ChipAttributionEntry> attributionAfter
) {
}
