package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 批量禁运设置响应：回显规范化后的条目集合（按 observationId 升序）与设置时刻。
 *
 * @param requestId 请求标识
 * @param operator  操作者标识
 * @param setAtUtc  禁运设置时刻（UTC）
 * @param itemCount 实际设置的条目数
 * @param items     已设置的禁运条目，按 observationId 升序
 */
public record EmbargoResponse(
        String requestId,
        String operator,
        Instant setAtUtc,
        int itemCount,
        List<EmbargoEntry> items) {

    /**
     * 单个已设置禁运条目。
     *
     * @param observationId   观测记录唯一标识
     * @param embargoUntilUtc 禁运截止时刻（UTC），区间左闭右开
     * @param disclosureScope 披露范围
     */
    public record EmbargoEntry(
            String observationId,
            Instant embargoUntilUtc,
            String disclosureScope) {
    }
}