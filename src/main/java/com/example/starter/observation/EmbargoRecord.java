package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变禁运设置记录：对应 observation_embargo 表的一行，设置后不可变。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC），左闭右开区间右端点，到达即失效
 * @param scope           披露范围标签集合（已去重排序）
 * @param operator        设置禁运的操作者标识
 * @param requestId       设置禁运的请求标识
 * @param setAtUtc        禁运设置时刻（UTC）
 */
public record EmbargoRecord(
        String observationId,
        Instant embargoUntilUtc,
        List<String> scope,
        String operator,
        String requestId,
        Instant setAtUtc) {
}
