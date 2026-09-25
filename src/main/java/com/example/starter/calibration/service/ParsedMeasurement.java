package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.calibration.model.Certificate;

/**
 * 一次测量提交解析后的规范化输入及其最终引用证书（测量服务内部使用）。
 *
 * @param measurementKey 幂等测量键
 * @param instrumentId   仪器/被测对象 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificate    按最终引用解析出的、测量时刻有效的证书
 */
record ParsedMeasurement(
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        BigDecimal reading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        Certificate certificate) {
}
