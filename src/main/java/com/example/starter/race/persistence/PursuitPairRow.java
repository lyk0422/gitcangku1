package com.example.starter.race.persistence;

/**
 * pursuit_pair 表行记录。
 *
 * @param raceId        所属赛事ID
 * @param pairNo        追逐对序号，从1连续递增
 * @param chaserBib     追赶者参赛号
 * @param chasedBib     被追者参赛号
 * @param chaserStartMm 追赶者起始距离（毫米，非负）
 * @param chasedStartMm 被追者起始距离（毫米，非负）
 * @param createdAt     配置时间，Unix毫秒时间戳
 * @param createdBy     配置操作者
 */
public record PursuitPairRow(
        String raceId,
        int pairNo,
        String chaserBib,
        String chasedBib,
        long chaserStartMm,
        long chasedStartMm,
        long createdAt,
        String createdBy) {
}
