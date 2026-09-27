package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量弃用窗口校验失败的结构化明细，保证失败原因可区分：
 * 定位首个失败项（按名称、版本稳定排序），并返回实际值、要求值或差额。
 *
 * @param reason             失败原因码：SUBJECT_MISSING/REPLACEMENT_MISSING/REPLACEMENT_WITHDRAWN/
 *                           EFFECTIVE_IN_PAST/DUPLICATE_ITEM/REPLACEMENT_CYCLE
 * @param itemIndex          失败项在排序后批次中的下标（从 0 起）；与具体项无关时为 null
 * @param secondsDifference   时间类失败的实际秒数差额：生效时刻早于当前时钟为正数，其余场景为 null
 * @param cycle              形成替代环的版本号序列（首尾同点）；无环时为 null
 * @param requestedCount     批次要求条数
 * @param uniqueCount        批次按名称+版本去重后的实际条数；非重复类失败时与 requestedCount 相等
 */
public record DeprecationFailureDetails(
        String reason,
        Integer itemIndex,
        String name,
        Integer version,
        Integer replacementVersion,
        Instant effectiveAt,
        Instant now,
        Long secondsDifference,
        List<Integer> cycle,
        int requestedCount,
        Integer uniqueCount) {
}
