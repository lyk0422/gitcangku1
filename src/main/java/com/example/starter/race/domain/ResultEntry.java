package com.example.starter.race.domain;

/**
 * 单个选手的成绩条目。
 *
 * @param bib           参赛号
 * @param rank          名次，从1开始；并列同名次且跳号（1、1、3）；UNTIMED/DISQUALIFIED 为 null
 * @param status        成绩状态
 * @param finishTimeMs  原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs     全部未撤销加时处罚的合计毫秒数；无加时为 0
 * @param totalTimeMs   总耗时=原始完赛耗时+生效加时（毫秒）；未排名时为 null
 */
public record ResultEntry(
        String bib,
        Integer rank,
        EntryStatus status,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs
) {
}
