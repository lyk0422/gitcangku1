package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 参考点线性拟合响应。提交与批准均返回冻结后的完整拟合视图；
 * 同 requestId 同参重放返回首次完整响应。
 *
 * @param id              拟合 ID
 * @param requestId       全局请求 ID（幂等键）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param a               拟合系数 a（十进制字符串，最多 6 位小数）
 * @param b               拟合偏移 b（十进制字符串，最多 6 位小数）
 * @param residualLimit   非负残差上限（十进制字符串）
 * @param maxAbsResidual  各点绝对残差最大值（十进制字符串）
 * @param expectedVersion 调用方期望版本；未提供为 null
 * @param submittedBy     提交人
 * @param approvedBy      批准人；未批准为 null
 * @param approvedAt      批准时间（UTC）；未批准为 null
 * @param status          状态：PENDING 待批准 / APPROVED 已批准
 * @param certificateId   批准时创建的证书 ID；未批准为 null，撤证不改写
 * @param points          冻结的参考点与残差评分（按输入顺序）
 * @param createdAt       提交时间（UTC）
 */
public record FitResponse(
        long id,
        String requestId,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String a,
        String b,
        String residualLimit,
        String maxAbsResidual,
        Long expectedVersion,
        String submittedBy,
        String approvedBy,
        Instant approvedAt,
        String status,
        Long certificateId,
        List<FitPointResponse> points,
        Instant createdAt) {
}
