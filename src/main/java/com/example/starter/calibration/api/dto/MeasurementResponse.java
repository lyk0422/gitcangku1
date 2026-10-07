package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值（线性）或反解根夹逼中点（二次）；
 * displayValue 为 HALF_UP 保留 4 位的显示值；usable 表示"当前可用"（已放行且证书未撤销）。
 * 二次模式输出提交时冻结的分支方向、根夹逼区间与端点函数值（精确合格依据），线性模式对应字段为 null。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param mode           匹配证书模式：LINEAR / QUADRATIC
 * @param reading        原始读数或信号 y（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  未舍入计算值或根夹逼中点（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（精确判定，含端点）
 * @param branch         二次分支单调方向：INCREASING / DECREASING；线性为 null
 * @param rootLower      二次反解根夹逼区间下界（十进制字符串）；线性为 null
 * @param rootUpper      二次反解根夹逼区间上界，宽度不大于 0.000001；线性为 null
 * @param evalLower      二次模式 f(lo) 精确值（十进制字符串）；线性为 null
 * @param evalUpper      二次模式 f(hi) 精确值（十进制字符串）；线性为 null
 * @param status         状态：PENDING / RELEASED
 * @param usable         当前是否可用（已放行且证书未撤销）
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        String mode,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String computedValue,
        String displayValue,
        boolean passed,
        String branch,
        String rootLower,
        String rootUpper,
        String evalLower,
        String evalUpper,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
