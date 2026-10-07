package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入值（二次模式为根的近似值，不参与判定）；
 * displayValue 为 HALF_UP 保留 4 位的显示值；usable 表示“当前可用”（已放行且证书未撤销）。
 * 二次反解模式额外携带精确合格依据（evalLo/evalHi）与根夹逼区间（rootLower/rootUpper）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（线性）或校准后信号 y（二次反解），十进制字符串
 * @param lowerLimit     合格下限（含端点），十进制字符串
 * @param upperLimit     合格上限（含端点），十进制字符串
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param certificateType 证书类型：LINEAR / QUADRATIC
 * @param computedValue  未舍入计算值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param evalLo         二次模式：f(lo) 精确函数值（合格依据）；线性为 null
 * @param evalHi         二次模式：f(hi) 精确函数值（合格依据）；线性为 null
 * @param rootLower      二次模式：根夹逼区间下界；线性为 null
 * @param rootUpper      二次模式：根夹逼区间上界（宽度 ≤ 0.000001）；线性为 null
 * @param passed         是否合格（基于精确值，含端点）
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
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String certificateType,
        String computedValue,
        String displayValue,
        String evalLo,
        String evalHi,
        String rootLower,
        String rootUpper,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
