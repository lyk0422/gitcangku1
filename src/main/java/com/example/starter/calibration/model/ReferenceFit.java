package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 参考点线性拟合记录。提交时冻结参考点、系数与残差；批准后登记证书 ID。
 * 历史不被后续撤证改写。
 *
 * @param id              拟合记录 ID（自增）
 * @param requestId       提交请求的全局请求 ID（幂等键）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param maxResidual     允许的最大绝对残差上限（非负）
 * @param a               拟合系数 a（精确，最多 6 位小数）
 * @param b               拟合偏移 b（精确，最多 6 位小数）
 * @param maxAbsResidual  各参考点绝对残差的最大值（精确）
 * @param pointsJson      冻结的参考点及残差（JSON 数组）
 * @param submittedBy     拟合提交人
 * @param status          状态：PENDING 待批准 / APPROVED 已批准
 * @param certificateId   批准时创建的证书 ID；未批准为 null
 * @param approvedBy      批准人；未批准为 null
 * @param approvedAt      批准时间（UTC）；未批准为 null
 * @param createdAt       提交时间（UTC）
 */
public record ReferenceFit(
        long id,
        String requestId,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal maxResidual,
        BigDecimal a,
        BigDecimal b,
        BigDecimal maxAbsResidual,
        String pointsJson,
        String submittedBy,
        FitStatus status,
        Long certificateId,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt) {
}
