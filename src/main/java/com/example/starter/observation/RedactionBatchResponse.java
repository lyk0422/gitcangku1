package com.example.starter.observation;

import java.util.List;

/**
 * 批量脱敏提交响应：返回要求条目数与实际生成版本数，调用方无需猜测差额。
 *
 * @param observationId 所属观测记录唯一标识
 * @param requestId     请求标识
 * @param ruleVersion   使用的规则版本
 * @param requestedCount 本次请求要求脱敏的附件数量
 * @param createdCount   本次实际新生成脱敏版本的数量（全部成功时等于 requestedCount）
 * @param versions      本次生成（或同参重放时原有）的脱敏版本明细，按附件标识排序，字段稳定
 */
public record RedactionBatchResponse(
        String observationId,
        String requestId,
        String ruleVersion,
        int requestedCount,
        int createdCount,
        List<RedactionVersionResponse> versions) {
}
