package com.example.starter.calibration.api.dto;

/**
 * 追加会话条目请求。首条必须显式给出 direction（UP/DOWN）；后续条目不得携带 direction，
 * 方向由系统按原值比较推导：增大选 UP、减小选 DOWN、相同继承前方向。
 *
 * @param requestId              幂等请求键，全局唯一
 * @param expectedSessionVersion 期望会话版本（等于当前已追加条目数），不一致返回 409
 * @param rawValue               原始读数，最多 6 位小数的十进制字符串
 * @param direction              首条显式方向：UP / DOWN；后续条目必须为 null
 * @param submittedBy            提交人
 */
public record AppendEntryRequest(
        String requestId,
        Long expectedSessionVersion,
        String rawValue,
        String direction,
        String submittedBy) {
}
