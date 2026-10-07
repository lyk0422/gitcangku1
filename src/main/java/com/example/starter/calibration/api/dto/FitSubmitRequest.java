package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 参考点线性拟合提交请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一）；
 * points 为 3～8 对 (x, y)，x 互不相同，均为最多 6 位小数的十进制字符串。
 *
 * @param requestId       全局请求 ID（幂等键，非空）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param points          参考点对（3～8 对，x 互不相同）
 * @param residualLimit   非负残差上限，十进制字符串
 * @param expectedVersion 调用方期望版本，仅参与幂等参数比较；可为 null
 * @param submittedBy     提交人（非空操作者）
 */
public record FitSubmitRequest(
        String requestId,
        String instrumentId,
        String validFrom,
        String validTo,
        List<FitPointInput> points,
        String residualLimit,
        Long expectedVersion,
        String submittedBy) {
}
