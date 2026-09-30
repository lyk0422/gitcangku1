package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 环境补偿放行成功响应（首次或同快照重放均返回首次结果）。
 *
 * @param requestId  放行请求 ID
 * @param batchId    放行批次 ID（UUID），写入放行历史
 * @param releasedBy 放行人（X-Actor-Id）
 * @param releasedAt 首次放行时间（UTC）
 * @param results    各测量并列的原读数、环境读数、补偿值、最终值与系数版本
 */
public record EnvironmentReleaseResponse(
        String requestId,
        String batchId,
        String releasedBy,
        Instant releasedAt,
        List<ItemResult> results) {

    /**
     * 单条补偿结果。
     *
     * @param measurementKey    测量键
     * @param reading           原始读数（十进制字符串）
     * @param temperature       放行温度（℃）
     * @param humidity          放行湿度（%RH）
     * @param compensationValue 未舍入补偿值（温差×温度系数＋湿度差×湿度系数）
     * @param finalValue        最终结果（计算值＋补偿值，HALF_UP 4 位小数）
     * @param coeffVersionId    引用的系数版本 ID
     */
    public record ItemResult(
            String measurementKey,
            String reading,
            String temperature,
            String humidity,
            String compensationValue,
            String finalValue,
            long coeffVersionId) {
    }
}
