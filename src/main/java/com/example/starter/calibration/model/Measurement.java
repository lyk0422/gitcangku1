package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。普通测量提交时按测量时刻匹配唯一有效证书并固化计算结果；
 * 会话成员由校准会话追加产生（sessionId 非空），禁止通过单测量放行接口放行。
 *
 * @param id             测量记录 ID（自增）
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）；会话成员为 null
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param submittedBy    提交人
 * @param certificateId  提交时匹配到的校准证书 ID；会话成员为会话固定证书
 * @param computedValue  未舍入计算值（线性 a×读数+b；迟滞 斜率×读数+方向偏移）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING 待放行 / RELEASED 已放行
 * @param sessionId      所属校准会话 ID；普通测量为 null
 * @param direction      读数变化方向 UP/DOWN；仅会话成员有值
 * @param createdAt      提交时间（UTC）
 */
public record Measurement(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String submittedBy,
        long certificateId,
        BigDecimal computedValue,
        boolean passed,
        MeasurementStatus status,
        Long sessionId,
        String direction,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
