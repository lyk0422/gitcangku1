package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 证据裁决快照响应（不可变）。
 *
 * @param adjudicationId 裁决ID
 * @param raceId         所属赛事ID
 * @param finishTimeMs   被裁决计时组共享的原始完赛耗时（毫秒）
 * @param evidenceIds    本次裁决覆盖的证据ID列表
 * @param finalOrder     裁决后的名次顺序（名次互不重复）
 * @param operator       裁决操作者
 * @param raceVersion    裁决完成后的赛事版本号
 * @param createdAt      裁决时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinishAdjudicationResponse(
        String adjudicationId,
        String raceId,
        long finishTimeMs,
        List<String> evidenceIds,
        List<String> finalOrder,
        String operator,
        int raceVersion,
        long createdAt
) {
}
