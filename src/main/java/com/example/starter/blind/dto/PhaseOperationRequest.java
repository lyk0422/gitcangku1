package com.example.starter.blind.dto;

/**
 * 阶段开始/结束请求体（可空）。
 *
 * @param expectedVersion 期望的阶段状态版本号；提供时与当前版本不一致返回 409，
 *                        属于幂等参数指纹的一部分
 */
public record PhaseOperationRequest(Long expectedVersion) {
}
