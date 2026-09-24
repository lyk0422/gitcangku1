package com.example.starter.race.persistence;

/**
 * advancement_entry 表行记录（晋级名单快照条目，生成后不可变）。
 *
 * @param advancementKey 所属晋级名单键
 * @param bib            晋级选手参赛号
 * @param groupCode      所属分组代码
 * @param type           晋级类型：DIRECT-组内直接晋级，WILDCARD-全局补位
 * @param rankNo         名次：DIRECT 为组内名次，WILDCARD 为补位候选池名次；并列同名次并跳号
 * @param finishTimeMs   生成时固化的原始完赛耗时（毫秒）
 * @param penaltyMs      生成时固化的生效加时合计（毫秒）
 * @param totalTimeMs    生成时固化的总耗时（毫秒）
 * @param displayOrder   展示顺序，从0开始
 */
public record AdvancementEntryRow(
        String advancementKey,
        String bib,
        String groupCode,
        String type,
        int rankNo,
        long finishTimeMs,
        long penaltyMs,
        long totalTimeMs,
        int displayOrder
) {
}
