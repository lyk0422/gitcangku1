package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 更正附页响应：附页链节点与提交/批量提交结果共用。
 *
 * @param observationId 观测记录唯一标识
 * @param corrVersion   附页版本号
 * @param baseVersion   提交时指定并校验的原观测版本号
 * @param diffs         字段差异（字段名 → 原值/更正值）
 * @param reason        更正原因
 * @param collector     采集者标识
 * @param status        附页状态：VALID / REVOKED
 * @param corrKey       幂等键
 * @param createdAtUtc  附页提交时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorrigendumResponse(
        String observationId,
        int corrVersion,
        int baseVersion,
        Map<String, FieldDiff> diffs,
        String reason,
        String collector,
        String status,
        String corrKey,
        Instant createdAtUtc) {

    /**
     * 由附页记录构造响应。
     */
    public static CorrigendumResponse of(CorrigendumEntry entry) {
        return new CorrigendumResponse(entry.observationId(), entry.corrVersion(), entry.baseVersion(),
                entry.diffs(), entry.reason(), entry.collector(), entry.status(), entry.corrKey(),
                entry.createdAtUtc());
    }
}
