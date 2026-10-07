package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 参考点线性拟合历史。提交时冻结参考点、系数与残差评分；
 * 批准一次创建不可变证书，历史不被后续撤证改写。
 *
 * @param id              拟合 ID（自增）
 * @param requestId       提交该拟合的全局请求 ID（幂等键）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param a               拟合系数 a，精确可表示为最多 6 位小数
 * @param b               拟合偏移 b，精确可表示为最多 6 位小数
 * @param residualLimit   非负残差上限
 * @param maxAbsResidual  各点绝对残差最大值（精确值）
 * @param pointCount      参考点对数量（3～8）
 * @param expectedVersion 调用方期望版本，仅参与幂等参数比较；未提供为 null
 * @param submittedBy     提交人
 * @param approvedBy      批准人（须不同于提交人）；未批准为 null
 * @param approvedAt      批准时间（UTC）；未批准为 null
 * @param status          状态：PENDING 待批准 / APPROVED 已批准
 * @param certificateId   批准时创建的证书 ID；未批准为 null，撤证不改写
 * @param createdAt       提交时间（UTC）
 */
public record ReferenceFit(
        long id,
        String requestId,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        BigDecimal residualLimit,
        BigDecimal maxAbsResidual,
        int pointCount,
        Long expectedVersion,
        String submittedBy,
        String approvedBy,
        Instant approvedAt,
        FitStatus status,
        Long certificateId,
        Instant createdAt) {
}
