package com.example.starter.observation;

/**
 * 单条消息的收件结果：携带该消息的观测业务响应；replayed 为 true 表示同序号同内容命中既有收件记录，
 * 未重复执行观测业务。
 *
 * @param deviceId    设备标识
 * @param sessionId   会话标识
 * @param seq         消息序号
 * @param replayed    是否命中既有收件记录（未重复改变观测）
 * @param observation 观测业务响应
 */
public record MessageReceipt(
        String deviceId,
        String sessionId,
        int seq,
        boolean replayed,
        ObservationResponse observation) {
}