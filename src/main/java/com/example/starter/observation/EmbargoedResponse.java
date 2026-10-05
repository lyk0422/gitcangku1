package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 禁运期内的公开查询响应：只返回禁运标记与截止时刻，不返回任何簇内容。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoed       是否处于禁运期：恒为 true
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601），左闭右开区间右端点
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoedResponse(
        String observationId,
        boolean embargoed,
        Instant embargoUntilUtc) implements PublicObservationView {
}
