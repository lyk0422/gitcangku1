package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变禁运设置记录：对应 observation_embargo 表的一行，设置后永不修改。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC）；禁运区间左闭右开，到达该时刻即解禁
 * @param disclosureScope 披露范围（JSON 数组原文，元素经过去空白、去重、排序，非空）
 * @param operator        设置禁运的操作者标识
 * @param versionAtSet    设置禁运时观测记录的当前版本号
 * @param requestId       设置禁运的请求标识
 * @param setAtUtc        禁运设置时刻（UTC）
 */
public record EmbargoRecord(
        String observationId,
        Instant embargoUntilUtc,
        String disclosureScope,
        String operator,
        int versionAtSet,
        String requestId,
        Instant setAtUtc) {
}