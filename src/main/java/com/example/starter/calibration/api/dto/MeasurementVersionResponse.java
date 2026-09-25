package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量版本链中的一个版本。
 *
 * @param versionNo             版本号，从 1 起
 * @param temperature           环境温度（摄氏度，十进制字符串）；未记录环境为 null
 * @param humidity              环境相对湿度（%RH，十进制字符串）；未记录环境为 null
 * @param uncertainty           测量不确定度（十进制字符串）；未提供为 null
 * @param certificateId         该版本匹配的校准证书 ID（标准器血缘）
 * @param computedValue         未舍入证书计算值（十进制字符串）
 * @param compensationProfileId 固化的补偿系数版本 ID；未补偿为 null
 * @param compensationVersionNo 固化的补偿系数型号内版本号；未补偿为 null
 * @param compensatedValue      补偿后测量值（6 位小数字符串）；未补偿为 null
 * @param passed                证书计算值是否合格
 * @param compensatedPassed     补偿后值是否合格；未补偿为 null
 * @param parentVersionNo       上一版本号；v1 为 null
 * @param calcKey               生成该版本的计算指纹键
 * @param createdAt             版本生成时间（UTC）
 */
public record MeasurementVersionResponse(
        int versionNo,
        String temperature,
        String humidity,
        String uncertainty,
        long certificateId,
        String computedValue,
        Long compensationProfileId,
        Integer compensationVersionNo,
        String compensatedValue,
        boolean passed,
        Boolean compensatedPassed,
        Integer parentVersionNo,
        String calcKey,
        Instant createdAt) {
}
