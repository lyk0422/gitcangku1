package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 回执请求：result 为 SUCCESS 或 FAILED，首次回执终结任务。
 *
 * <p>新租约模式：任务一旦被任一代理领取过，回执必须同时携带非空 agentId 与正数 token，
 * 且为当前有效租约持有人；旧回执入口（不带凭据）不能绕过该模式，返回 409。
 * 从未被领取的任务保持旧入口受理规则。agentId/token 均为 null 表示旧入口；只给其一时按 400 处理。</p>
 *
 * @param requestId 全局唯一请求ID
 * @param result    回执结果：SUCCESS 或 FAILED
 * @param agentId   回执代理ID；租约模式任务必填，必须为当前有效租约持有人
 * @param token     回执租约令牌；租约模式任务必填，必须与当前租约令牌一致
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull ReceiptResult result,
        @Size(max = 64) String agentId,
        @Positive Integer token) {

    /**
     * 旧回执入口兼容构造：不带租约凭据。
     */
    public ReceiptRequest(String requestId, ReceiptResult result) {
        this(requestId, result, null, null);
    }

    /**
     * 是否携带了完整租约凭据。
     */
    public boolean hasLeaseCredentials() {
        return agentId != null && !agentId.isBlank() && token != null;
    }
}
