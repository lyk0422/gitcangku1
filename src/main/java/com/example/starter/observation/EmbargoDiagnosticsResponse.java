package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 禁运诊断查询响应：禁运设置、到期状态与发布状态的全量诊断信息，时间均为 UTC。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoSet      是否已设置禁运
 * @param embargoUntilUtc 禁运截止时刻（UTC）；未设置为 null
 * @param disclosureScope 披露范围；未设置为 null
 * @param operator        设置禁运的操作者标识；未设置为 null
 * @param setAtUtc        禁运设置时刻（UTC）；未设置为 null
 * @param nowUtc          诊断生成时刻（UTC）
 * @param expired         禁运是否已到期（now &gt;= until，区间右开）；未设置为 null
 * @param remainingMillis 距到期的剩余毫秒数（实际值，到期后为 0）；未设置为 null
 * @param openConflicts   当前未解决冲突数量（实际值）
 * @param released        是否已生成首次披露快照
 * @param releasedAtUtc   首次公开发布时刻（UTC）；未发布为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoDiagnosticsResponse(
        String observationId,
        boolean embargoSet,
        Instant embargoUntilUtc,
        String disclosureScope,
        String operator,
        Instant setAtUtc,
        Instant nowUtc,
        Boolean expired,
        Long remainingMillis,
        int openConflicts,
        boolean released,
        Instant releasedAtUtc) {
}