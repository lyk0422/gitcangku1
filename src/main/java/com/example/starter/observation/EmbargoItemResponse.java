package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 单簇禁运设置响应项。
 *
 * @param observationId           观测记录唯一标识
 * @param embargoUntilUtc         禁运截止时刻（UTC，ISO-8601）
 * @param disclosureScope         规范化后的披露范围标识列表（去空白、去重、排序）
 * @param versionAtSet            设置禁运时观测记录的当前版本号
 * @param setAtUtc                禁运设置时刻（UTC，ISO-8601）
 * @param embargoDurationSeconds  禁运时长实际值（秒）：截止时刻减去设置时刻
 */
public record EmbargoItemResponse(
        String observationId,
        Instant embargoUntilUtc,
        List<String> disclosureScope,
        int versionAtSet,
        Instant setAtUtc,
        long embargoDurationSeconds) {
}