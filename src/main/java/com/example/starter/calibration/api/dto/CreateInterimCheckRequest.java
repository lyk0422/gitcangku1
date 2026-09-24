package com.example.starter.calibration.api.dto;

/**
 * 期间核查提交请求。标准值、实测值、容差均为最多 6 位小数的十进制字符串。
 *
 * @param requestId     写操作请求幂等键；同参重放返回首次结果，异参返回 409，失败不占键
 * @param checkKey      核查键，全局唯一；同一 checkKey 最多生效一次
 * @param instrumentId  仪器 ID
 * @param checkedAt     核查时刻，ISO-8601（按 UTC 归一）
 * @param standardValue 标准值，十进制字符串
 * @param measuredValue 实测值，十进制字符串
 * @param tolerance     容差，非负十进制字符串
 * @param checkedBy     核查人
 */
public record CreateInterimCheckRequest(
        String requestId,
        String checkKey,
        String instrumentId,
        String checkedAt,
        String standardValue,
        String measuredValue,
        String tolerance,
        String checkedBy) {
}
