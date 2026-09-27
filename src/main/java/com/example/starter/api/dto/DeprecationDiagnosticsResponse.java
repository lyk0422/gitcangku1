package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 弃用窗口诊断视图：按查询时刻的实际状态计数，读取不改变状态。
 *
 * @param now 诊断时刻（UTC）
 * @param total 窗口总数（含已撤销）
 * @param pending 尚未生效的有效窗口数（now &lt; effectiveAt）
 * @param effective 已生效的有效窗口数（now &gt;= effectiveAt，左闭）
 * @param revoked 已撤销窗口数
 * @param effectiveWindows 当前已生效窗口明细，按名称、版本升序
 */
public record DeprecationDiagnosticsResponse(
        Instant now,
        int total,
        int pending,
        int effective,
        int revoked,
        List<DeprecationResponse> effectiveWindows) {
}
