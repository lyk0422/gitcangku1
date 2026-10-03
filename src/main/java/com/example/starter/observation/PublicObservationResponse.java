package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 公开查询响应：未设禁运的簇返回当前内容（OPEN）；已发布的簇返回首次快照与全部附页（RELEASED）。
 * 禁运截止前或到期未发布时不返回内容（由 403 错误响应表达）。
 *
 * @param observationId 观测记录唯一标识
 * @param state         公开状态：OPEN（无禁运）/ RELEASED（已发布）
 * @param version       内容来源版本号（OPEN 为当前版本，RELEASED 为发布时胜出版本）
 * @param deleted       内容来源版本是否为删除墓碑
 * @param location      观测地点（墓碑为 null）
 * @param reading       观测读数（墓碑为 null）
 * @param note          观测备注（墓碑为 null）
 * @param releasedAtUtc 首次发布时刻（UTC）；OPEN 为 null
 * @param appendices    更正附页，按 seq 升序；OPEN 时为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicObservationResponse(
        String observationId,
        String state,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        Instant releasedAtUtc,
        List<AppendixResponse> appendices) {
}