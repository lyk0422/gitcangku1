package com.example.starter.maintenance.api.dto;

/**
 * 单条读数的登记诊断结果（批量预校验失败时逐条返回，全部失败统一 422）。
 *
 * @param readingId          读数标识
 * @param accepted          是否可接受
 * @param reasonCode        不可接受原因码（accepted=true 时为 null）：
 *                          READING_ID_DUPLICATE / OUT_OF_WINDOW / BELOW_BASELINE /
 *                          READING_ORDER_VIOLATION / READING_TIME_DUPLICATE
 * @param message           原因说明
 */
public record ReadingDiagnostic(
        String readingId,
        boolean accepted,
        String reasonCode,
        String message) {
}
