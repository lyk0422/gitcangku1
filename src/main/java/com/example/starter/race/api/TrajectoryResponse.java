package com.example.starter.race.api;

import com.example.starter.race.domain.TrajectoryPoint;

import java.util.List;

/**
 * 轨迹响应。
 *
 * @param raceId            赛事ID
 * @param bib               选手参赛号
 * @param trajectoryVersion 当前轨迹版本（首次采样为1）
 * @param samples           当前采样点（按时刻升序；隐含起点固定为(0,0)）
 */
public record TrajectoryResponse(
        String raceId,
        String bib,
        int trajectoryVersion,
        List<TrajectoryPoint> samples
) {
}
