package com.example.starter.race.persistence;

/**
 * chip_replacement 表行记录：一次芯片更换的固化历史。
 *
 * <p>历史字段（芯片代码、生效时刻、裁判、重算前后归属）写入后不再被后续更换改写，
 * 排序按自增 id（即提交顺序）稳定。
 *
 * @param id                 自增主键，稳定排序键
 * @param replacementId      更换记录ID，全局唯一
 * @param raceId             所属赛事ID
 * @param bib                更换芯片的选手参赛号
 * @param oldChipCode        更换前芯片代码（固化）
 * @param newChipCode        更换后芯片代码（固化）
 * @param effectiveAt       UTC 生效时刻（毫秒），区间左闭右开边界
 * @param referee            裁判确认人（操作者账号）
 * @param beforeAttribution  重算前各分段归属的规范化 JSON 文本
 * @param afterAttribution   重算后各分段归属的规范化 JSON 文本
 * @param createdAt          更换提交时间，Unix毫秒时间戳（UTC）
 */
public record ChipReplacementRow(
        long id,
        String replacementId,
        String raceId,
        String bib,
        String oldChipCode,
        String newChipCode,
        long effectiveAt,
        String referee,
        String beforeAttribution,
        String afterAttribution,
        long createdAt
) {
}
