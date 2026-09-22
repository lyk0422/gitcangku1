package com.example.starter.api.dto;

/**
 * 写操作通用响应；幂等重放时返回首次成功的同一份结果。
 *
 * @param requestId 写操作请求标识
 * @param replayed  true 表示为同键同参重放（非首次执行）
 * @param data      操作结果数据（各接口结构不同）
 */
public record MutationResponse(String requestId, boolean replayed, Object data) {
}
