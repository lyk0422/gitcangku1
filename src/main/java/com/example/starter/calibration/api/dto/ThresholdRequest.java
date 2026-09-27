package com.example.starter.calibration.api.dto;

/**
 * 仪器不确定度阈值设置请求。每次成功调用生成一个递增的新版本，旧版本不改写。
 *
 * @param requestId      幂等请求 ID（必填；同参重放返回首次快照，异参 409，失败不占键）
 * @param maxUncertainty 合成不确定度上限（非负十进制字符串，最多 6 位小数，含端点）
 */
public record ThresholdRequest(String requestId, String maxUncertainty) {
}
