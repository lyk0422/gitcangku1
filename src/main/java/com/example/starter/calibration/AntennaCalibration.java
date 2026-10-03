package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 天线指向校准记录。
 *
 * @param id 校准编号（主键）
 * @param antennaId 所属天线主键
 * @param calibratedAt 校准时刻，UTC
 * @param azimuthDeviationDeg 方位偏差，单位度
 * @param elevationDeviationDeg 俯仰偏差，单位度
 * @param passed 是否通过；两个绝对偏差均不超过0.2度为通过
 * @param validFrom 有效期起点，UTC；未通过时为 null
 * @param validUntil 有效期终点，UTC；通过后为校准时刻+24小时；未通过时为 null
 * @param status 校准状态
 * @param invalidatedAt 作废时刻，UTC；未作废为 null
 * @param invalidateReason 作废原因；未作废为 null
 */
public record AntennaCalibration(
        Long id,
        long antennaId,
        Instant calibratedAt,
        BigDecimal azimuthDeviationDeg,
        BigDecimal elevationDeviationDeg,
        boolean passed,
        Instant validFrom,
        Instant validUntil,
        CalibrationStatus status,
        Instant invalidatedAt,
        String invalidateReason) {
}