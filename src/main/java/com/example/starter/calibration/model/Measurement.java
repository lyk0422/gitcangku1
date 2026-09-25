package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 测量记录（版本行）。提交时按测量时刻匹配唯一有效证书并固化基础计算结果；
 * 记录环境时按型号当前生效系数版本固化环境补偿与补偿后值；重算在同一事务内追加新版本行。
 *
 * @param id              测量记录版本行 ID（自增）
 * @param rootId          逻辑测量 ID（首版本行 ID，重算版本沿用）
 * @param versionNo       版本号，从 1 递增
 * @param measurementKey   业务测量键（逻辑测量标识，幂等键）
 * @param instrumentId    仪器 ID
 * @param instrumentModel 仪器型号；记录环境时非空
 * @param measuredAt      测量时刻（UTC）
 * @param rawReading     原始读数，最多 6 位小数
 * @param lowerLimit    合格下限（含端点）
 * @param upperLimit    合格上限（含端点）
 * @param uncertaintyLimit 放行批次不确定度上限（绝对值）；可空
 * @param env          记录环境（型号、温度、湿度）；未记录为 null
 * @param submittedBy    提交人
 * @param certificateId   匹配到的校准证书 ID（标准器血缘，固化）
 * @param coefficientId   环境补偿系数版本 ID 快照；未补偿为 null
 * @param computedValue  未舍入基础计算值 a×读数+b
 * @param compensationValue 补偿值，按系数与环境计算并四舍五入到 6 位小数；未补偿为 null
 * @param compensatedValue 补偿后测量值=基础值+补偿值（精确）；未补偿为 null
 * @param uncertainty    扩展不确定度（非负）；未评估为 null
 * @param passed        基础值是否合格（未舍入、含端点）
 * @param passedAfterComp 补偿后是否超规格（含端点）；未补偿为 null
 * @param status        状态：PENDING / RELEASED / REJECTED
 * @param rejectedBy    驳回人；未驳回为 null
 * @param rejectedAt    驳回时间（UTC）；未驳回为 null
 * @param rejectReason  驳回原因；未驳回为 null
 * @param createdAt      本版本创建时间（UTC）
 */
public record Measurement(
        long id,
        long rootId,
        int versionNo,
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        Instant measuredAt,
        BigDecimal rawReading,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        BigDecimal uncertaintyLimit,
        EnvRecord env,
        String submittedBy,
        long certificateId,
        Long coefficientId,
        BigDecimal computedValue,
        BigDecimal compensationValue,
        BigDecimal compensatedValue,
        BigDecimal uncertainty,
        boolean passed,
        Boolean passedAfterComp,
        MeasurementStatus status,
        String rejectedBy,
        Instant rejectedAt,
        String rejectReason,
        Instant createdAt) {

    /** 是否记录了测量环境（温度与湿度同时记录）。 */
    public boolean hasEnvironment() {
        return env != null;
    }

    /** 是否计算并固化了环境补偿。 */
    public boolean compensated() {
        return compensationValue != null;
    }

    /**
     * 放行判定使用的值：有补偿时为补偿后测量值，否则为未舍入基础计算值。
     */
    public BigDecimal releaseValue() {
        return compensatedValue != null ? compensatedValue : computedValue;
    }

    /**
     * 显示值：未舍入基础计算值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return computedValue.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 补偿后显示值：补偿后测量值按 HALF_UP 保留 6 位小数；未补偿为 null。
     */
    public BigDecimal displayCompensatedValue() {
        return compensatedValue == null ? null : compensatedValue.setScale(6, RoundingMode.HALF_UP);
    }
}
