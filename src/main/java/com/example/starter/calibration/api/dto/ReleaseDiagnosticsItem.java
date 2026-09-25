package com.example.starter.calibration.api.dto;

/**
 * 放行诊断中的单条测量追溯项。
 *
 * @param measurementKey     测量键
 * @param certificateId      放行时引用的证书 ID
 * @param certVersion        放行时引用的证书版本
 * @param compensationCoeff  放行时的补偿系数（十进制字符串）
 * @param uncertaintyVersion 放行时的不确定度版本
 * @param singleBatchOnly    证书是否单批次独占
 * @param boundBatchId       证书绑定的放行批次 ID，未绑定为 null
 */
public record ReleaseDiagnosticsItem(
        String measurementKey,
        long certificateId,
        String certVersion,
        String compensationCoeff,
        String uncertaintyVersion,
        boolean singleBatchOnly,
        String boundBatchId) {
}
