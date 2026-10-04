package com.example.starter.observation;

/**
 * 会话收件记录：对应 session_inbox 表的一行，按设备会话内序号唯一。
 *
 * @param seq                会话内消息序号，正整数
 * @param contentFingerprint 消息内容结构化指纹（SHA-256），同序号异内容判定 409
 * @param batchRequestId     接收该消息的批次请求标识
 * @param messageStatus      该消息观测业务响应的 HTTP 状态码
 * @param messageBody        该消息观测业务响应体（JSON 原文），用于同序号同内容重放
 */
public record InboxMessage(
        int seq,
        String contentFingerprint,
        String batchRequestId,
        int messageStatus,
        String messageBody) {
}