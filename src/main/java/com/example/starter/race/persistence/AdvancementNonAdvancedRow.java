package com.example.starter.race.persistence;

/**
 * advancement_list_non_advanced 表行记录（名单生成时同步固化的未晋级有效选手）。
 *
 * @param advancementKey 所属名单键
 * @param raceId         所属赛事ID
 * @param bib            参赛号
 * @param groupCode      生成时所属分组代码
 * @param rank           组内成绩名次，并列同名次并跳号
 * @param finishTimeMs   固化的原始完赛耗时（毫秒）
 * @param penaltyMs      固化的生效加时合计毫秒数
 * @param totalTimeMs    固化的总耗时（毫秒）
 * @param displayOrder   展示顺序，从0开始
 */
public record AdvancementNonAdvancedRow(
        String advancementKey,
        String raceId,
        String bib,
        String groupCode,
        int rank,
        long finishTimeMs,
        long penaltyMs,
        long totalTimeMs,
        int displayOrder
) {
}
