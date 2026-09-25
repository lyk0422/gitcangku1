package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个波次视图。
 *
 * @param waveKey   波次唯一键
 * @param startMs   波次 UTC 起跑时刻，Unix 毫秒时间戳
 * @param bibs      参赛者参赛号集合，按参赛号字典序排列
 * @param createdAt 波次登记时间，Unix 毫秒时间戳
 * @param updatedAt 波次最近修改时间，Unix 毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaveResponse(
        String waveKey,
        long startMs,
        List<String> bibs,
        long createdAt,
        long updatedAt
) {
}
