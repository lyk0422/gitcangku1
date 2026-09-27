package com.example.starter.maintenance.api;

/**
 * 错误明细条目：用于 422 等响应列出缺失/失败项。数量类问题给出实际值、要求值与差额。
 *
 * @param type       明细类型（如 MISSING_REQUIRED_ITEM / FAILED_ITEM / REVOKED_MATERIAL / EQUIPMENT_OVERDUE）
 * @param target     涉及对象标识（检查项编码、材料批次或设备标识），无对象时为 null
 * @param reason     人类可读原因说明
 * @param actual     实际值（数量/时长类问题），不适用为 null
 * @param required   要求值（数量/时长类问题），不适用为 null
 * @param difference 差额（actual - required），不适用为 null
 */
public record ErrorDetail(String type, String target, String reason,
                          Long actual, Long required, Long difference) {

    /** 无数量信息的明细条目。 */
    public static ErrorDetail of(String type, String target, String reason) {
        return new ErrorDetail(type, target, reason, null, null, null);
    }

    /** 带数量信息的明细条目。 */
    public static ErrorDetail ofQuantity(String type, String target, String reason,
                                         long actual, long required) {
        return new ErrorDetail(type, target, reason, actual, required, actual - required);
    }
}
