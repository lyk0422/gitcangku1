package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 * certVersion、compensationCoeff、uncertaintyVersion 为当前版本引用的证书快照，保证完整可追溯。
 *
 * @param id                 测量记录 ID
 * @param measurementKey     业务测量键
 * @param batchId            提交批次 ID，未指定为 null
 * @param referenceKey       幂等引用键，未提供为 null
 * @param instrumentId       仪器 ID
 * @param measuredAt         测量时刻（UTC）
 * @param reading            原始读数（十进制字符串）
 * @param lowerLimit         合格下限（十进制字符串）
 * @param upperLimit         合格上限（十进制字符串）
 * @param submittedBy        提交人
 * @param certificateId      当前版本引用的证书 ID
 * @param certVersion        当前版本引用的证书版本
 * @param compensationCoeff  当前版本补偿系数（十进制字符串）
 * @param uncertaintyVersion 当前版本不确定度版本
 * @param computedValue      未舍入计算值（十进制字符串）
 * @param uncertainty        当前版本不确定度（十进制字符串）
 * @param displayValue       显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed             是否合格（基于未舍入值，含端点）
 * @param version            当前测量版本号
 * @param status             状态：PENDING / RELEASED
 * @param usable             当前是否可用（已放行且证书未撤销）
 * @param createdAt          提交时间（UTC）
 * @param releases           放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String batchId,
        String referenceKey,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String certVersion,
        String compensationCoeff,
        String uncertaintyVersion,
        String computedValue,
        String uncertainty,
        String displayValue,
        boolean passed,
        int version,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
