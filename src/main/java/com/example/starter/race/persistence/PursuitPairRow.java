package com.example.starter.race.persistence;

/**
 * pursuit_pair 表行记录：追逐对声明（启用 PURSUIT 模式时固定，1~5对）。
 *
 * @param raceId         所属赛事ID
 * @param pairId         追逐对ID，赛事内唯一
 * @param pursuerBib     追赶者参赛号
 * @param pursuedBib     被追者参赛号
 * @param pursuerStartMm 追赶者起始距离（毫米，非负整数）
 * @param pursuedStartMm 被追者起始距离（毫米，非负整数）
 * @param createdAt      配置时间，Unix毫秒时间戳
 */
public record PursuitPairRow(
        String raceId,
        String pairId,
        String pursuerBib,
        String pursuedBib,
        long pursuerStartMm,
        long pursuedStartMm,
        long createdAt) {
}
