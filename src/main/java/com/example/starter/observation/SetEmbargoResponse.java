package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 批量设置禁运响应：携带实际生效数量与要求数量，以及每项的不可变设置结果。
 *
 * @param requestId      请求标识
 * @param operator       操作者标识
 * @param requestedCount 本次请求的禁运项数量（要求值）
 * @param appliedCount   实际生效的禁运项数量（全部成功才提交，否则整次回滚）
 * @param items          各簇的禁运设置结果（按 observationId 字典序排序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetEmbargoResponse(
        String requestId,
        String operator,
        int requestedCount,
        int appliedCount,
        List<Item> items) {

    /**
     * 单簇禁运设置结果。
     *
     * @param observationId   观测记录唯一标识（簇）
     * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）
     * @param disclosureScope 披露范围标签（已规范化：去空白、去重、排序）
     * @param versionAtSet    设置时簇的当前版本号
     * @param setAtUtc        设置完成时刻（UTC，ISO-8601）
     */
    public record Item(
            String observationId,
            Instant embargoUntilUtc,
            List<String> disclosureScope,
            int versionAtSet,
            Instant setAtUtc) {
    }
}
