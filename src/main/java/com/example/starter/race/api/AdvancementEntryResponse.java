package com.example.starter.race.api;

/**
 * 晋级名单条目（快照固化，生成后不随后续计时修订、处罚或封榜改变）。
 *
 * @param bib          晋级选手参赛号
 * @param groupCode    所属分组代码
 * @param type         DIRECT-组内直接晋级，WILDCARD-全局补位
 * @param rank         名次：DIRECT 为组内名次，WILDCARD 为补位候选池名次；并列同名次并跳号
 * @param finishTimeMs 生成时固化的原始完赛耗时（毫秒）
 * @param penaltyMs    生成时固化的生效加时合计（毫秒）
 * @param totalTimeMs  生成时固化的总耗时（毫秒）
 */
public record AdvancementEntryResponse(
        String bib,
        String groupCode,
        String type,
        int rank,
        long finishTimeMs,
        long penaltyMs,
        long totalTimeMs
) {
}
