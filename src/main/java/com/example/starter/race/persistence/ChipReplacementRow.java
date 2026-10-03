package com.example.starter.race.persistence;

import com.example.starter.race.domain.ChipTimeline;

/**
 * chip_replacement 表行记录：一次芯片更换的固化证据。
 *
 * @param replacementId     更换记录ID，全局唯一
 * @param raceId            所属赛事ID
 * @param bib               选手参赛号
 * @param oldChip           旧芯片ID；首次更换时同时固化该选手初始芯片
 * @param newChip           新芯片ID
 * @param effectiveAt       新芯片生效时刻，Unix毫秒时间戳（UTC），区间左闭右开
 * @param referee           确认本次更换的裁判
 * @param attributionBefore 重算前该选手全部分段读数的芯片归属JSON
 * @param attributionAfter  重算后该选手全部分段读数的芯片归属JSON
 * @param createdAt         更换登记时间，Unix毫秒时间戳
 */
public record ChipReplacementRow(
        String replacementId,
        String raceId,
        String bib,
        String oldChip,
        String newChip,
        long effectiveAt,
        String referee,
        String attributionBefore,
        String attributionAfter,
        long createdAt
) implements ChipTimeline.ChipReplacementView {
}
