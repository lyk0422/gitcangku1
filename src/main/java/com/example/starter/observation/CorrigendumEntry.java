package com.example.starter.observation;

import java.time.Instant;
import java.util.Map;

/**
 * 观测更正附页：对应 corrigendum 表的一行。原始观测不可覆盖，附页按版本递增保存字段差异。
 *
 * @param observationId 观测记录唯一标识
 * @param corrVersion   附页版本号，每个观测记录内从 1 开始单调递增
 * @param baseVersion   提交时指定并校验的原观测版本号
 * @param diffs         字段差异（键为字段名，固定顺序 location/reading/note；值为原值/更正值）
 * @param reason        更正原因
 * @param collector     采集者标识
 * @param corrKey       幂等键，同键重放、失败不占键
 * @param status        附页状态：VALID 有效 / REVOKED 已撤销
 * @param createdAtUtc  附页提交时刻（UTC）
 */
public record CorrigendumEntry(
        String observationId,
        int corrVersion,
        int baseVersion,
        Map<String, FieldDiff> diffs,
        String reason,
        String collector,
        String corrKey,
        String status,
        Instant createdAtUtc) {

    /**
     * 有效附页状态。
     */
    public static final String STATUS_VALID = "VALID";

    /**
     * 已撤销附页状态。
     */
    public static final String STATUS_REVOKED = "REVOKED";
}
