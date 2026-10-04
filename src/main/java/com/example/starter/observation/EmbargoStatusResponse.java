package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 禁运诊断响应：汇报簇当前的禁运、发布与冲突状态；时长类字段给出实际值。
 *
 * @param observationId       观测记录唯一标识
 * @param nowUtc              诊断时刻（UTC，ISO-8601）
 * @param embargoSet          是否已设置禁运
 * @param embargoUntilUtc     禁运截止时刻（UTC；未设置禁运时为 null）
 * @param setAtUtc            禁运设置时刻（UTC；未设置禁运时为 null）
 * @param embargoActive       诊断时刻是否仍处于禁运期（左闭右开：到达截止时刻即解禁）
 * @param remainingSeconds    距解禁的剩余秒数实际值；已解禁时为负值（绝对值为已过去秒数）；未设置禁运时为 null
 * @param released            是否已生成首次发布快照
 * @param releasedAtUtc       首次发布时刻（UTC；未发布时为 null）
 * @param unresolvedConflict  是否存在未解决冲突
 * @param conflictFields      未解决冲突字段名列表（无冲突时为 null）
 * @param appendixCount       当前附页数量实际值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoStatusResponse(
        String observationId,
        Instant nowUtc,
        boolean embargoSet,
        Instant embargoUntilUtc,
        Instant setAtUtc,
        boolean embargoActive,
        Long remainingSeconds,
        boolean released,
        Instant releasedAtUtc,
        boolean unresolvedConflict,
        List<String> conflictFields,
        int appendixCount) {
}