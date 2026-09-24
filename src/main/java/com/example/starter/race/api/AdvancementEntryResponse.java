package com.example.starter.race.api;

/**
 * 单条晋级结果（固化于不可变快照中）。
 *
 * @param bib          参赛号
 * @param groupCode    生成时所属分组代码
 * @param rank         名次：DIRECT 为组内名次，WILDCARD 为跨组全局名次，并列同名次并跳号
 * @param type         DIRECT-直接晋级，WILDCARD-跨组补位
 * @param finishTimeMs 生成时刻固化的原始完赛耗时（毫秒）
 * @param penaltyMs    生成时刻固化的生效加时合计毫秒数
 * @param totalTimeMs  生成时刻固化的总耗时（毫秒，含加时）
 */
public record AdvancementEntryResponse(
        String bib,
        String groupCode,
        int rank,
        String type,
        long finishTimeMs,
        long penaltyMs,
        long totalTimeMs
) {
}
