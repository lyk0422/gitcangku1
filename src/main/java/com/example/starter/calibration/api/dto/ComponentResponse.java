package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 不确定度分量响应。
 *
 * @param name      分量名称
 * @param value     分量标准不确定度（十进制字符串）
 * @param ordinal   提交序号，从 0 起
 * @param createdAt 分量固化时间（UTC）
 */
public record ComponentResponse(String name, String value, int ordinal, Instant createdAt) {
}
