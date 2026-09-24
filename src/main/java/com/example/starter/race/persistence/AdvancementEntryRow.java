package com.example.starter.race.persistence;

import com.example.starter.race.domain.AdvancementEntryType;

/**
 * advancement_list_entry 表行记录（不可变晋级快照中的单条结果）。
 *
 * @param advancementKey 所属名单键
 * @param raceId         所属赛事ID
 * @param bib            参赛号
 * @param groupCode      生成时所属分组代码
 * @param rank           DIRECT 为组内名次，WILDCARD 为跨组全局名次，并列同名次并跳号
 * @param type           DIRECT / WILDCARD
 * @param finishTimeMs   固化的原始完赛耗时（毫秒）
 * @param penaltyMs      固化的生效加时合计毫秒数
 * @param totalTimeMs    固化的总耗时（毫秒）
 * @param displayOrder   展示顺序，从0开始
 */
public record AdvancementEntryRow(
        String advancementKey,
        String raceId,
        String bib,
        String groupCode,
        int rank,
        AdvancementEntryType type,
        long finishTimeMs,
        long penaltyMs,
        long totalTimeMs,
        int displayOrder
) {
}
