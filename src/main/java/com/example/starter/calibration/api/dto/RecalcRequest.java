package com.example.starter.calibration.api.dto;

/**
 * 重算请求：对未放行（待放行/已驳回）的逻辑测量提交重算。
 * 重算在一个事务内用当前生效系数版本生成新测量版本并重新评估整批（给定批次键集合）。
 *
 * @param measurementKey 逻辑测量键（会生成新版本）
 * @param batchKeys    重新评估整批的测量键集合（1～50，不可重复）；必须包含 measurementKey
 * @param uncertaintyLimit 批次不确定度上限（非负十进制字符串）；可空，空表示不启用不确定度门禁
 * @param calcKey      幂等键；同键同指纹重放首次成功结果，失败不占键；可空
 */
public record RecalcRequest(
        String measurementKey,
        java.util.List<String> batchKeys,
        String uncertaintyLimit,
        String calcKey) {
}
