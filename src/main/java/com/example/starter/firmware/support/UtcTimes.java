package com.example.starter.firmware.support;

import com.example.starter.firmware.error.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * UTC 时刻工具：所有业务时刻统一解析、输出为 UTC（ISO-8601，结尾 Z）。
 * 时间区间一律左闭右开：豁免在 [createdAt, expiresAt) 内有效，expiresAt 当刻已失效。
 */
public final class UtcTimes {

    private UtcTimes() {
    }

    /**
     * 解析 ISO-8601 时刻并归一化为 UTC；非法格式返回 422（INVALID_INSTANT）。
     */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalid(value);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(value);
        }
    }

    private static ApiException invalid(String value) {
        return ApiException.unprocessable("INVALID_INSTANT",
                "时刻必须为 UTC ISO-8601（如 2026-10-01T00:00:00Z），非法时刻: " + value);
    }

    public static String now(Clock clock) {
        return Instant.now(clock).toString();
    }
}
