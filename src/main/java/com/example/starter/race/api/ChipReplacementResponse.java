package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 一次芯片更换的固化响应；也是历史查询中单条记录的结构。
 *
 * @param replacementId     更换记录ID
 * @param raceId            所属赛事ID
 * @param bib               选手参赛号
 * @param oldChipCode       更换前芯片代码（固化）
 * @param newChipCode       更换后芯片代码（固化）
 * @param effectiveAtMs     UTC 生效时刻（毫秒），左闭右开边界
 * @param referee           裁判确认人
 * @param createdAt         更换提交时间，Unix毫秒时间戳（UTC）
 * @param beforeAttribution 重算前各分段归属，按检查点顺序
 * @param afterAttribution  重算后各分段归属，按检查点顺序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipReplacementResponse(
        String replacementId,
        String raceId,
        String bib,
        String oldChipCode,
        String newChipCode,
        long effectiveAtMs,
        String referee,
        long createdAt,
        List<ChipAttributionItem> beforeAttribution,
        List<ChipAttributionItem> afterAttribution
) {
}
