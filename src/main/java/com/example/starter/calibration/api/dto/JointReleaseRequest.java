package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 跨仪器联合批次放行请求。
 *
 * @param jointBatchKey 联合批次业务键，全局唯一
 * @param requestId     请求幂等键；同键同参重放返回首次响应快照，异参 409
 * @param keys          测量标识列表，2～20 条，允许来自不同仪器；换序视为同参
 */
public record JointReleaseRequest(String jointBatchKey, String requestId, List<String> keys) {
}
