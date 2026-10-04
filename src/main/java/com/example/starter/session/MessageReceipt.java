package com.example.starter.session;

import com.example.starter.observation.ObservationResponse;

/**
 * 单条消息的收件回执：观测业务结果或同 seq 同内容的重放结果。
 *
 * @param seq         会话内消息序号
 * @param replayed    是否为同 seq 同内容的重放（未重复改变观测）
 * @param status      观测业务响应的 HTTP 状态码
 * @param observation 观测业务响应体
 */
public record MessageReceipt(
        int seq,
        boolean replayed,
        int status,
        ObservationResponse observation) {
}