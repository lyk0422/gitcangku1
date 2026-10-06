package com.example.starter.calibration.api.dto;

/**
 * 会话条目响应。correctedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值。
 *
 * @param seq            会话内序号（从 1 开始）
 * @param rawValue       原始值（十进制字符串）
 * @param direction      方向 UP/DOWN
 * @param correctedValue 未舍入校正值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    条目提交人
 * @param measurementKey 关联测量记录的测量键
 */
public record SessionEntryResponse(
        int seq,
        String rawValue,
        String direction,
        String correctedValue,
        String displayValue,
        boolean passed,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String measurementKey) {
}
