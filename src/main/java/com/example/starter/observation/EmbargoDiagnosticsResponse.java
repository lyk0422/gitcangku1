package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 禁运诊断查询响应：返回判定所需的实际值与差额，字段为 null 时省略。
 *
 * @param observationId   观测记录唯一标识
 * @param nowUtc          诊断计算时刻（UTC）
 * @param embargoSet      是否已设置禁运
 * @param embargoUntilUtc 禁运截止时刻（UTC）；未设禁运为 null
 * @param expired         禁运是否已到期（now &gt;= embargoUntil）；未设禁运为 null
 * @param remainingMillis 距到期的剩余毫秒数（实际值，到期后为 0）；未设禁运为 null
 * @param released        是否已完成首次发布
 * @param releasedAtUtc   首次发布时刻（UTC）；未发布为 null
 * @param appendixCount   附页总数（实际值）
 * @param pendingConflict 是否存在未解决冲突
 * @param conflictFields  未解决冲突字段名列表；无未解决冲突为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoDiagnosticsResponse(
        String observationId,
        Instant nowUtc,
        boolean embargoSet,
        Instant embargoUntilUtc,
        Boolean expired,
        Long remainingMillis,
        boolean released,
        Instant releasedAtUtc,
        int appendixCount,
        boolean pendingConflict,
        List<String> conflictFields) {
}