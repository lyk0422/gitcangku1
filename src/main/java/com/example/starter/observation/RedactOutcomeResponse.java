package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 批量脱敏结果：整次成功时返回每个附件新生成的脱敏版本。
 * requestedCount 与 createdCount 必须相等（整次失败时不返回本响应）。
 *
 * @param observationId  观测记录标识
 * @param ruleVersion    使用的规则版本
 * @param requestedCount 本次请求包含的附件数（要求值）
 * @param createdCount   实际生成的脱敏版本数（实际值）
 * @param versions       各附件新生成的脱敏版本明细
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactOutcomeResponse(
        String observationId,
        String ruleVersion,
        int requestedCount,
        int createdCount,
        List<RedactionVersionResponse> versions) {
}
