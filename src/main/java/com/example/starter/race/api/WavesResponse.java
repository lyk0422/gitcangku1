package com.example.starter.race.api;

import java.util.List;

/**
 * 波次清单响应。
 *
 * @param raceId      赛事ID
 * @param version     当前赛事版本号
 * @param baseStartAt 赛事基准起跑时刻，Unix毫秒UTC时间戳
 * @param waves       波次列表，按波次起跑时刻、波次键稳定排序
 */
public record WavesResponse(
        String raceId,
        int version,
        long baseStartAt,
        List<WaveResponse> waves
) {
}
