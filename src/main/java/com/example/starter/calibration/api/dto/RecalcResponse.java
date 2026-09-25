package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 重算结果：新测量版本明细 + 重新评估整批的放行诊断（不自动放行）。
 *
 * @param measurementKey 逻辑测量键
 * @param versionNo      新版本号
 * @param measurement  新版本测量明细
 * @param diagnostics   整批逐测量放行诊断（稳定字典序）
 * @param recalculatedAt 重算时间（UTC）
 */
public record RecalcResponse(
        String measurementKey,
        int versionNo,
        MeasurementResponse measurement,
        List<ReleaseDiagnosticItem> diagnostics,
        Instant recalculatedAt) {
}
