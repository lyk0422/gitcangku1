package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 校准查询视图，含有效期与绑定影响。
 *
 * @param id 校准编号
 * @param antennaCode 天线编号
 * @param calibratedAt 校准时刻，UTC
 * @param azimuthDeviationDeg 方位偏差，单位度
 * @param elevationDeviationDeg 俯仰偏差，单位度
 * @param passed 是否通过
 * @param validFrom 有效期起点，UTC；未通过为 null
 * @param validUntil 有效期终点，UTC；未通过为 null
 * @param status 校准状态
 * @param invalidatedAt 作废时刻，UTC；未作废为 null
 * @param invalidateReason 作废原因；未作废为 null
 * @param bindings 引用该校准的绑定列表
 */
public record CalibrationView(
        long id,
        String antennaCode,
        Instant calibratedAt,
        BigDecimal azimuthDeviationDeg,
        BigDecimal elevationDeviationDeg,
        boolean passed,
        Instant validFrom,
        Instant validUntil,
        CalibrationStatus status,
        Instant invalidatedAt,
        String invalidateReason,
        List<BindingView> bindings) {
}