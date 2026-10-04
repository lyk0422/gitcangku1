package com.example.starter.observation;

import java.util.List;

/**
 * 批次上传响应：按请求顺序返回每条消息的收件结果，并附本批涉及会话在处理后的水位快照。
 * 同键同参重放时返回首次完整响应（含首次水位），不返回当前水位。
 *
 * @param requestId 批次请求标识
 * @param messages  每条消息的收件结果，顺序与请求一致
 * @param sessions  本批涉及的会话状态快照（按设备、会话排序）
 */
public record BatchUploadResponse(
        String requestId,
        List<MessageReceipt> messages,
        List<SessionStateResponse> sessions) {
}