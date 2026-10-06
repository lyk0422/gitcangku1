package com.example.starter.race.api;

/**
 * 追逐对响应。
 *
 * @param pairNo        追逐对序号
 * @param chaserBib     追赶者参赛号
 * @param chasedBib     被追者参赛号
 * @param chaserStartMm 追赶者起始距离（毫米）
 * @param chasedStartMm 被追者起始距离（毫米）
 */
public record PursuitPairResponse(
        int pairNo,
        String chaserBib,
        String chasedBib,
        long chaserStartMm,
        long chasedStartMm
) {
}
