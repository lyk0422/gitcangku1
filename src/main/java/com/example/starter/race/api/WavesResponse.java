package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事波次清单响应。
 *
 * @param raceId   赛事ID
 * @param version  当前赛事版本号（只读查询不修改版本）
 * @param waves    全部波次，按 waveKey 字典序排列
 */
public record WavesResponse(
        String raceId,
        int version,
        List<WaveResponse> waves
) {
}
