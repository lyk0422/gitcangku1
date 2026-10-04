package com.example.starter.observation;

/**
 * 批次中单条消息的处理结果。
 *
 * @param deviceId    设备标识
 * @param sessionId   会话标识
 * @param seq         会话内消息序号
 * @param status      观测业务响应的 HTTP 状态码
 * @param replayed    是否命中既有收件记录（未重复执行观测业务）
 * @param observation 观测业务响应体
 */
public record MessageResult(
        String deviceId,
        String sessionId,
        int seq,
        int status,
        boolean replayed,
        ObservationResponse observation) {
}