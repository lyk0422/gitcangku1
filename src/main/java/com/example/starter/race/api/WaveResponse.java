package com.example.starter.race.api;

import java.util.List;

/**
 * 单个波次视图。
 *
 * @param waveKey 波次唯一键
 * @param startAt 波次UTC起跑时刻，Unix毫秒时间戳
 * @param runners 波次内参赛者参赛号，按字典序排列
 */
public record WaveResponse(
        String waveKey,
        long startAt,
        List<String> runners
) {
}
