package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值：LINEAR 为十进制字符串，CURVE 为既约分数 "num/den"；
 * displayValue 为 HALF_UP 保留 4 位的显示值；usable 表示“当前可用”（已放行且证书未撤销）。
 * interval 为 CURVE 测量冻结的相邻节点区间（落在单节点时上下节点相同），LINEAR 为 null。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param certType       匹配证书类型：LINEAR / CURVE
 * @param computedValue  未舍入精确值（LINEAR 十进制；CURVE 既约分数 "num/den"）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param interval       CURVE 冻结节点区间；LINEAR 为 null
 * @param passed         是否合格（基于未舍入精确值，含端点）
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
        String certType,
        String computedValue,
        String displayValue,
        FrozenInterval interval,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {

    /**
     * CURVE 测量冻结的相邻节点区间；落在单节点时上下节点相同。
     *
     * @param loRaw       下节点原始值（十进制字符串）
     * @param loCorrected 下节点校正值（十进制字符串）
     * @param hiRaw       上节点原始值（十进制字符串）
     * @param hiCorrected 上节点校正值（十进制字符串）
     */
    public record FrozenInterval(String loRaw, String loCorrected, String hiRaw, String hiCorrected) {
    }
}
