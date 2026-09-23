package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 赛事申诉证据列表响应（只读）；按受理时间与申诉键稳定排序。
 *
 * @param raceId  所属赛事ID
 * @param appeals 申诉证据列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppealsResponse(
        String raceId,
        List<AppealResponse> appeals
) {
}
