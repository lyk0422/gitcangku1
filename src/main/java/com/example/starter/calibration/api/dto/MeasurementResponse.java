package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入证书计算值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * compensatedValue 为按记录环境与系数版本计算的补偿后值（6 位小数）；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id                    测量记录 ID
 * @param measurementKey        业务测量键
 * @param instrumentId          仪器 ID
 * @param instrumentModel       仪器型号；未提供为 null
 * @param measuredAt            测量时刻（UTC）
 * @param reading               原始读数（十进制字符串）
 * @param lowerLimit            合格下限（十进制字符串）
 * @param upperLimit            合格上限（十进制字符串）
 * @param temperature           环境温度（十进制字符串）；未记录环境为 null
 * @param humidity              环境相对湿度（十进制字符串）；未记录环境为 null
 * @param uncertainty           测量不确定度（十进制字符串）；未提供为 null
 * @param submittedBy           提交人
 * @param certificateId         当前版本匹配到的证书 ID
 * @param computedValue         未舍入证书计算值（十进制字符串）
 * @param displayValue          显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed                证书计算值是否合格（含端点）
 * @param compensationProfileId 当前版本固化的补偿系数版本 ID；未补偿为 null
 * @param compensationVersionNo 当前版本固化的补偿系数型号内版本号；未补偿为 null
 * @param compensatedValue      补偿后测量值（6 位小数字符串）；未补偿为 null
 * @param compensatedPassed     补偿后值是否合格；未补偿为 null
 * @param currentVersion        当前测量版本号
 * @param status                状态：PENDING / RELEASED / REJECTED
 * @param usable                当前是否可用（已放行且证书未撤销）
 * @param createdAt             提交时间（UTC）
 * @param versions              重算链（按版本号升序）
 * @param releases              放行历史
 * @param rejects               驳回历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String temperature,
        String humidity,
        String uncertainty,
        String submittedBy,
        long certificateId,
        String computedValue,
        String displayValue,
        boolean passed,
        Long compensationProfileId,
        Integer compensationVersionNo,
        String compensatedValue,
        Boolean compensatedPassed,
        int currentVersion,
        String status,
        boolean usable,
        Instant createdAt,
        List<MeasurementVersionResponse> versions,
        List<ReleaseRecordResponse> releases,
        List<RejectRecordResponse> rejects) {
}
