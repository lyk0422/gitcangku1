package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测更正附页记录：对应 observation_corrigendum 表的一行，追加后不可变（仅 revoked 标志随撤销更新）。
 *
 * @param observationId  观测记录唯一标识
 * @param corrVersion    附页版本号，同一观测记录内从 1 开始单调递增
 * @param corrKey        客户端提交的附页幂等键
 * @param baseVersion    附页指定的原观测版本号
 * @param diffs          字段差异（更正值）JSON 对象原文，按固定字段顺序，读数已数值规范化
 * @param originalValues 差异字段在原观测版本中的原值（JSON 对象原文）
 * @param reason         更正原因
 * @param collector      采集者标识
 * @param revoked        是否已撤销
 * @param createdAtUtc   附页提交时刻（UTC）
 */
public record CorrigendumRecord(
        String observationId,
        int corrVersion,
        String corrKey,
        int baseVersion,
        String diffs,
        String originalValues,
        String reason,
        String collector,
        boolean revoked,
        Instant createdAtUtc) {
}
