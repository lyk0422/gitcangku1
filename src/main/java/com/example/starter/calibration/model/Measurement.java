package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定；
 * 仪器已配置分辨率格点时，同时冻结配置版本、原点、步长、格点整数 k 与量化值。
 *
 * @param id                测量记录 ID（自增）
 * @param measurementKey    业务测量键，全局唯一（幂等键）
 * @param instrumentId      仪器 ID
 * @param measuredAt        测量时刻（UTC）
 * @param rawReading        原始读数，最多 6 位小数
 * @param lowerLimit        合格下限（含端点）
 * @param upperLimit        合格上限（含端点）
 * @param submittedBy       提交人
 * @param certificateId     提交时匹配到的校准证书 ID
 * @param computedValue     未舍入计算值 a×读数+b
 * @param passed            是否合格（基于未舍入值，含端点；已配置格点时精确值与量化值均须在区间内）
 * @param status            状态：PENDING 待放行 / RELEASED 已放行
 * @param resolutionVersion 提交时冻结的分辨率配置版本；未配置仪器为 null
 * @param gridOrigin        提交时冻结的格点原点 o；未配置为 null
 * @param gridStep          提交时冻结的格点步长 s（正数）；未配置为 null
 * @param gridK             提交时冻结的格点整数 k=(x-o)/s 最近整数（半格取偶）；未配置为 null
 * @param quantizedValue    量化值 o+k×s；未配置仪器为 null
 * @param createdAt         提交时间（UTC）
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
        Integer resolutionVersion,
        BigDecimal gridOrigin,
        BigDecimal gridStep,
        BigDecimal gridK,
        BigDecimal quantizedValue,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
