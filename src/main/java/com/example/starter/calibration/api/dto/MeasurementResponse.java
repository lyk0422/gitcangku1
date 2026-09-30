package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * compensationValue 为环境补偿精确值，finalValue 为补偿后 HALF_UP 4 位最终值；
 * coefficientVersion 为放行时命中的系数版本标签；未放行时环境补偿相关字段为 null；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id                  测量记录 ID
 * @param measurementKey      业务测量键
 * @param instrumentId        仪器 ID
 * @param measuredAt          测量时刻（UTC）
 * @param reading             原始读数（十进制字符串）
 * @param lowerLimit          合格下限（十进制字符串）
 * @param upperLimit          合格上限（十进制字符串）
 * @param submittedBy         提交人
 * @param certificateId       匹配到的证书 ID
 * @param computedValue       未舍入计算值（十进制字符串）
 * @param displayValue        显示值，HALF_UP 4 位小数（十进制字符串）
 * @param temperature         放行时环境温度（℃）；未放行为 null
 * @param humidity            放行时环境湿度（%RH）；未放行为 null
 * @param compensationValue   环境补偿精确值（十进制字符串）；未放行为 null
 * @param finalValue          最终结果，HALF_UP 4 位小数（十进制字符串）；未放行为 null
 * @param coefficientVersion  放行命中系数版本标签；未放行为 null
 * @param passed              是否合格（基于未舍入值，含端点）
 * @param status              状态：PENDING / RELEASED
 * @param usable              当前是否可用（已放行且证书未撤销）
 * @param createdAt           提交时间（UTC）
 * @param releases            放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String computedValue,
        String displayValue,
        String temperature,
        String humidity,
        String compensationValue,
        String finalValue,
        String coefficientVersion,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
