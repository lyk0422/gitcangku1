package com.example.starter.race.api;

import java.util.List;

/**
 * 轨迹提交响应。
 *
 * @param raceId            赛事ID
 * @param bib               选手参赛号
 * @param trajectoryVersion 提交后的轨迹版本（从1开始）
 * @param samples           当前版本完整采样点（按提交顺序）
 */
public record TrajectoryResponse(
        String raceId,
        String bib,
        int trajectoryVersion,
        List<SamplePointResponse> samples
) {

    /**
     * 轨迹采样点视图。
     *
     * @param elapsedMs  从起跑起的累计毫秒
     * @param progressMm 累计前进毫米
     */
    public record SamplePointResponse(long elapsedMs, long progressMm) {
    }
}
