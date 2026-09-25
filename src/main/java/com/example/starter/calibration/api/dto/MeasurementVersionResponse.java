package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量血缘中的单个版本。
 *
 * @param version            版本号
 * @param certificateId      该版本引用的证书 ID
 * @param certVersion        该版本引用的证书版本
 * @param compensationCoeff  该版本补偿系数（十进制字符串）
 * @param uncertaintyVersion 该版本不确定度版本
 * @param computedValue      该版本未舍入计算值（十进制字符串）
 * @param uncertainty        该版本不确定度（十进制字符串）
 * @param passed             该版本是否合格
 * @param current            是否为当前有效版本
 * @param createdAt          版本生成时间（UTC）
 */
public record MeasurementVersionResponse(
        int version,
        long certificateId,
        String certVersion,
        String compensationCoeff,
        String uncertaintyVersion,
        String computedValue,
        String uncertainty,
        boolean passed,
        boolean current,
        Instant createdAt) {
}
