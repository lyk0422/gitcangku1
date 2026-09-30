package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录。读数超出允许区间时自动生成，封存目标值、阈值、读数与提交角色，一经写入不可变。
 * 裁决状态随 {@link OutlierReview} 的有效同议变化；结论相反时保持 PENDING（待仲裁）。
 *
 * @param id            离群记录 ID（自增）
 * @param outlierKey    离群业务键，全局唯一
 * @param batchId       所属批次 ID
 * @param revisionNo    检测时封存的测量修订版本号
 * @param measurementId 离群读数对应的测量记录 ID
 * @param targetValue   封存的目标值
 * @param tolerance     封存的允许偏差（非负）
 * @param lowerLimit    封存的允许下限（含端点）
 * @param upperLimit    封存的允许上限（含端点）
 * @param rawReading    封存的原始读数
 * @param computedValue 封存的未舍入计算值
 * @param deviation     有符号差额 = 计算值 - 目标值（负值表示低于目标）
 * @param excess        超出量 = |差额| - 允许偏差；离群必为正
 * @param submittedBy   封存的读数提交人（不得复核本记录）
 * @param status        裁决状态：PENDING / ACCEPTED / REJECTED
 * @param detectedAt    离群检测时间（UTC）
 * @param decidedAt     裁决形成时间（UTC）；未形成有效裁决为 null
 */
public record OutlierRecord(
        long id,
        String outlierKey,
        long batchId,
        int revisionNo,
        long measurementId,
        BigDecimal targetValue,
        BigDecimal tolerance,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        BigDecimal rawReading,
        BigDecimal computedValue,
        BigDecimal deviation,
        BigDecimal excess,
        String submittedBy,
        OutlierStatus status,
        Instant detectedAt,
        Instant decidedAt) {
}
