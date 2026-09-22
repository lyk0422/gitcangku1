package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事缺失检查点汇总响应；只统计已有完赛计时但未覆盖全部检查点的选手。
 *
 * @param raceId  赛事ID
 * @param runners 存在漏点的选手列表，按参赛号字典序排列
 */
public record MissingCheckpointsResponse(
        String raceId,
        List<RunnerMissingResponse> runners
) {
    /**
     * 单选手漏点明细。
     *
     * @param bib                参赛号
     * @param missingCheckpoints 缺失的检查点编码，按检查点顺序排列
     */
    public record RunnerMissingResponse(String bib, List<String> missingCheckpoints) {
    }
}
