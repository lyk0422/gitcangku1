package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量记录。提交时按测量时刻匹配唯一有效证书，并固化未舍入计算值与合格判定；
 * 指定标准器时同时绑定当时有效的标准器版本。
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
 * @param standardVersionId 提交时绑定的有效标准器版本 ID；未指定标准器为 null
 * @param computedValue     未舍入计算值 a×读数+b
 * @param passed            是否合格（基于未舍入值，含端点）
 * @param status            状态：PENDING 待放行 / RELEASED 已放行 / BLOCKED 失效阻断 / REVIEW_REQUIRED 失效待复审
 * @param impactVersion     命中失效冻结的影响版本号；未受影响为 null
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
        Long standardVersionId,
        BigDecimal computedValue,
        boolean passed,
        MeasurementStatus status,
        String impactVersion,
        Instant createdAt) {

    /**
     * 显示值：未舍入计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
