package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 参考点拟合响应。包含冻结的参考点、精确系数与残差；批准后携带证书 ID。
 *
 * @param id              拟合记录 ID
 * @param requestId       提交请求的全局请求 ID
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param maxResidual     允许的最大绝对残差上限
 * @param a               拟合系数 a（十进制字符串，精确）
 * @param b               拟合偏移 b（十进制字符串，精确）
 * @param maxAbsResidual  各参考点绝对残差的最大值（十进制字符串，精确）
 * @param points          冻结的参考点及残差
 * @param submittedBy     拟合提交人
 * @param status          状态：PENDING / APPROVED
 * @param certificateId   批准时创建的证书 ID；未批准为 null
 * @param approvedBy      批准人；未批准为 null
 * @param approvedAt      批准时间（UTC）；未批准为 null
 * @param createdAt       提交时间（UTC）
 */
public record FitResponse(
        long id,
        String requestId,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        String maxResidual,
        String a,
        String b,
        String maxAbsResidual,
        List<FitPointResult> points,
        String submittedBy,
        String status,
        Long certificateId,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt) {
}
