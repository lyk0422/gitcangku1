package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事申诉列表响应：按创建时间、申诉ID稳定排列。
 *
 * @param raceId   赛事ID
 * @param version  当前赛事版本号（即时视图）
 * @param protests 申诉列表（PENDING 在前，终态按创建时间排列）
 */
public record ProtestListResponse(
        String raceId,
        int version,
        List<ProtestResponse> protests
) {
}
