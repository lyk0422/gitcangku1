package com.example.starter.race.api;

import com.example.starter.race.domain.RaceMode;

import java.util.List;

/**
 * 启用追逐赛模式响应。
 *
 * @param raceId  赛事ID
 * @param version 启用后的赛事版本
 * @param mode    赛事模式（启用后固定为 PURSUIT）
 * @param pairs   已固定的追逐对声明（按 pairId 字典序）
 */
public record PursuitConfigResponse(
        String raceId,
        int version,
        RaceMode mode,
        List<PursuitPairResponse> pairs
) {

    /**
     * 追逐对声明视图。
     *
     * @param pairId         追逐对ID
     * @param pursuerBib     追赶者参赛号
     * @param pursuedBib     被追者参赛号
     * @param pursuerStartMm 追赶者起始距离（毫米）
     * @param pursuedStartMm 被追者起始距离（毫米）
     */
    public record PursuitPairResponse(
            String pairId,
            String pursuerBib,
            String pursuedBib,
            long pursuerStartMm,
            long pursuedStartMm
    ) {
    }
}
