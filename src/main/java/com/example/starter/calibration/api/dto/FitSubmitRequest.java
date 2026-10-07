package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 提交参考点拟合请求。数值均为最多 6 位小数的十进制字符串；时间须为带时区的 ISO-8601。
 *
 * @param requestId       全局请求 ID（幂等键，非空）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param maxResidual     允许的最大绝对残差上限（非负），十进制字符串
 * @param points          3～8 对参考点（x 互不相同）
 * @param submittedBy     拟合提交人（非空）
 * @param expectedVersion 期望版本（可空），参与幂等参数结构化比较
 */
public record FitSubmitRequest(
        String requestId,
        String instrumentId,
        String validFrom,
        String validTo,
        String maxResidual,
        List<FitPoint> points,
        String submittedBy,
        Long expectedVersion) {
}
