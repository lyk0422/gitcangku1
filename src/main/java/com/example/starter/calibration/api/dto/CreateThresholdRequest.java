package com.example.starter.calibration.api.dto;

/**
 * 新建仪器不确定度阈值版本请求。版本只增不改，区间为 UTC 左闭右开；
 * validTo 为 null 表示开放区间，同一仪器开放区间至多一个，区间不得重叠（相邻合法）。
 *
 * @param instrumentId   仪器 ID
 * @param validFrom      有效期起点（含），ISO-8601
 * @param validTo        有效期终点（不含），ISO-8601；null/缺省表示开放区间
 * @param maxUncertainty 合成不确定度上限阈值，非负、最多 6 位小数的十进制字符串
 */
public record CreateThresholdRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String maxUncertainty) {
}
