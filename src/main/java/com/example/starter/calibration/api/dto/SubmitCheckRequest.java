package com.example.starter.calibration.api.dto;

/**
 * 提交仪器期间核查请求。标准值、实测值、容差为最多 6 位小数的十进制字符串。
 *
 * @param requestId     写操作幂等键；同参重放返回首次结果，异参返回 409，失败不占键
 * @param checkKey      核查业务键，全局唯一
 * @param instrumentId  仪器 ID
 * @param checkedAt     UTC 核查时刻，ISO-8601 带时区
 * @param standardValue 标准值，十进制字符串
 * @param actualValue   实测值，十进制字符串
 * @param tolerance     容差（非负），十进制字符串
 * @param checkedBy     核查人
 */
public record SubmitCheckRequest(
        String requestId,
        String checkKey,
        String instrumentId,
        String checkedAt,
        String standardValue,
        String actualValue,
        String tolerance,
        String checkedBy) {
}
