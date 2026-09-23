package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 赛事申诉证据列表响应：按受理时间与申诉键稳定排序，只读。
 *
 * @param raceId  赛事ID
 * @param appeals 申诉证据列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealListResponse(
        String raceId,
        List<AppealResponse> appeals
) {
}
