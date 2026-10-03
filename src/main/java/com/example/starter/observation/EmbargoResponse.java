package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 禁运设置明细：禁运设置不可变，released/releasedAtUtc 反映该簇发布状态。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，左闭右开区间右端点）
 * @param disclosureScope 披露范围
 * @param operator        设置禁运的操作者标识
 * @param setAtUtc        禁运设置完成时刻（UTC）
 * @param released        是否已完成首次发布
 * @param releasedAtUtc   首次发布时刻（UTC）；未发布为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoResponse(
        String observationId,
        Instant embargoUntilUtc,
        String disclosureScope,
        String operator,
        Instant setAtUtc,
        boolean released,
        Instant releasedAtUtc) {
}