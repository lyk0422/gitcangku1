package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变禁运设置记录：对应 observation_embargo 表的一行，设置后永不修改。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC），区间 [setAtUtc, embargoUntilUtc) 左闭右开
 * @param disclosureScope 披露范围，非空
 * @param operator        设置禁运的操作者标识
 * @param requestId       设置禁运的批量请求标识
 * @param setAtUtc        禁运设置时刻（UTC）
 */
public record EmbargoRecord(
        String observationId,
        Instant embargoUntilUtc,
        String disclosureScope,
        String operator,
        String requestId,
        Instant setAtUtc) {
}