package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定。
 * 环境补偿放行成功后，temperature/humidity/coeffVersionId/compensationValue/finalValue 被固化，
 * 此后系数再调整也不重算；未放行时这五个字段为 null。
 *
 * @param id                 测量记录 ID（自增）
 * @param measurementKey     业务测量键，全局唯一（幂等键）
 * @param instrumentId       仪器 ID
 * @param measuredAt         测量时刻（UTC）
 * @param rawReading         原始读数，最多 6 位小数
 * @param lowerLimit         合格下限（含端点）
 * @param upperLimit         合格上限（含端点）
 * @param submittedBy        提交人
 * @param certificateId      提交时匹配到的校准证书 ID
 * @param computedValue      未舍入计算值 a×读数+b
 * @param passed             是否合格（基于未舍入值，含端点）
 * @param temperature        放行时环境温度（℃）；未放行或旧普通放行为 null
 * @param humidity           放行时环境湿度（%RH）；未放行或旧普通放行为 null
 * @param coeffVersionId     放行时引用的系数版本 ID；未放行为 null，放行后不变
 * @param compensationValue  未舍入环境补偿值（温差×温度系数＋湿度差×湿度系数）；未放行为 null
 * @param finalValue         最终结果，HALF_UP 4 位小数；放行时固化，未放行为 null
 * @param status             状态：PENDING 待放行 / RELEASED 已放行
 * @param createdAt          提交时间（UTC）
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
        BigDecimal temperature,
        BigDecimal humidity,
        Long coeffVersionId,
        BigDecimal compensationValue,
        BigDecimal finalValue,
        MeasurementStatus status,
        Instant createdAt) {

    /** 最终结果小数位：4 位。 */
    public static final int FINAL_SCALE = 4;

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(FINAL_SCALE, RoundingMode.HALF_UP);
    }
}
