package com.example.starter.blind.dto;

/**
 * 单条业务违规明细：用于退役/扩容等需要返回可区分原因的错误响应。
 * 数量类违规给出实际值、要求值与差额；非数量类违规三者均为 null。
 *
 * @param treatment  涉及的处理代码；与代码无关的违规为 null
 * @param reasonCode 可机读原因码，如 CODE_NOT_FOUND / CODE_ALREADY_RETIRED /
 *                   CODE_EXHAUSTED / PENDING_UNBLIND_REQUEST /
 *                   INSUFFICIENT_REMAINING_CODES / EXPERIMENT_CLOSED /
 *                   EXPANSION_EXCEEDS_MAX
 * @param message    安全提示信息（不含盲底映射）
 * @param actual     实际值；无数量语义时为 null
 * @param required   要求值；无数量语义时为 null
 * @param difference 差额（required - actual 的绝对缺口）；无数量语义时为 null
 */
public record ViolationView(
        String treatment,
        String reasonCode,
        String message,
        Long actual,
        Long required,
        Long difference
) {
}
