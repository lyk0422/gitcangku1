package com.example.starter.observation;

/**
 * 会话消息收件记录：对应 session_message 表的一行，仅成功批次落库。
 *
 * @param deviceId           设备标识
 * @param sessionId          会话标识
 * @param seq                消息序号
 * @param contentFingerprint 消息内容（操作+全部参数）结构化指纹
 * @param requestId          首次送达该消息的批次请求标识
 * @param responseStatus     该消息观测业务响应的 HTTP 状态码
 * @param responseBody       该消息观测业务响应（JSON 原文）
 */
public record SessionMessageRecord(
        String deviceId,
        String sessionId,
        int seq,
        String contentFingerprint,
        String requestId,
        int responseStatus,
        String responseBody) {
}