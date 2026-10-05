package com.example.starter.calibration.api.dto;

/**
 * 创建差分请求。限值为最多 6 位小数的十进制字符串，闭区间含端点。
 *
 * @param requestId       客户端请求 ID（幂等键）
 * @param differentialKey 业务差分键，全局唯一
 * @param referenceKey    参考件测量键
 * @param dutKey          待测件测量键
 * @param lowerLimit      差分合格下限（闭区间，含端点），十进制字符串
 * @param upperLimit      差分合格上限（闭区间，含端点），十进制字符串
 */
public record CreateDifferentialRequest(
        String requestId,
        String differentialKey,
        String referenceKey,
        String dutKey,
        String lowerLimit,
        String upperLimit) {
}
