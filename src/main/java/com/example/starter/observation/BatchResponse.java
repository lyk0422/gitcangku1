package com.example.starter.observation;

import java.util.List;

/**
 * 批次上传响应：逐消息结果及涉及会话的水位状态。
 * 会话状态为处理时刻的快照；同键重放返回首次保存的原始快照而非当前状态。
 *
 * @param requestId 批次请求标识
 * @param messages  逐消息处理结果，顺序与请求一致
 * @param sessions  本批涉及会话的水位状态
 */
public record BatchResponse(
        String requestId,
        List<MessageResult> messages,
        List<SessionStateView> sessions) {
}