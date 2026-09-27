package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 脱敏诊断查询结果（证据权限，只读）。时间区间按左闭右开 [from, to) 解释。
 * 数量类字段返回实际值；携带 expectedCount 时返回差额 difference = actualCount - expectedCount。
 *
 * @param observationId 观测记录标识
 * @param from          区间起点（UTC，含）；未指定为 null
 * @param to            区间终点（UTC，不含）；未指定为 null
 * @param actualCount   区间内实际生成的脱敏版本数（实际值）
 * @param expectedCount 调用方传入的要求值；未传入为 null
 * @param difference    差额 actualCount - expectedCount；未传 expectedCount 为 null
 * @param activeCount   当前公开展示资格有效的脱敏版本数
 * @param revokedCount  已撤销公开展示资格的脱敏版本数
 * @param versions      区间内脱敏版本明细（按生成时刻、附件、版本稳定排序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionDiagnosticsResponse(
        String observationId,
        Instant from,
        Instant to,
        int actualCount,
        Integer expectedCount,
        Integer difference,
        int activeCount,
        int revokedCount,
        List<RedactionVersionResponse> versions) {
}
