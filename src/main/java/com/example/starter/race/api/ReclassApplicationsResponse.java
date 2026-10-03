package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事重分申请列表响应。
 *
 * @param raceId       赛事ID
 * @param applications 全部申请，按提交时间与申请ID稳定排列
 */
public record ReclassApplicationsResponse(
        String raceId,
        List<ReclassApplicationResponse> applications
) {
}