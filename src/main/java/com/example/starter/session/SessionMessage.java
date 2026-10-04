package com.example.starter.session;

/**
 * 会话消息收件记录：对应 session_message 表的一行，仅成功接收的消息会落库。
 *
 * @param deviceId              设备标识
 * @param sessionId             会话标识
 * @param seq                   会话内消息序号，1..100
 * @param contentFingerprint    消息内容结构化指纹，同 seq 异内容判定 409
 * @param observationRequestId  消息内观测请求的 requestId
 * @param responseStatus        观测业务成功的 HTTP 状态码
 * @param responseBody          观测业务成功响应体（JSON 原文），用于同 seq 同内容重放
 */
public record SessionMessage(
        String deviceId,
        String sessionId,
        int seq,
        String contentFingerprint,
        String observationRequestId,
        int responseStatus,
        String responseBody) {
}