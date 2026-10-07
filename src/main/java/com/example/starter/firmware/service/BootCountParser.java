package com.example.starter.firmware.service;

import com.example.starter.firmware.error.ApiException;

/**
 * 启动计数文本解析：十进制按值比较，非数字或超出 long 范围返回 400。
 */
final class BootCountParser {

    private BootCountParser() {
    }

    /**
     * 解析非空十进制计数文本；缺失、非数字或超出 long 范围均为 400。
     */
    static long parseRequired(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("BOOT_COUNT_REQUIRED", "启动计数不能为空");
        }
        return parse(raw);
    }

    /**
     * 解析可空十进制计数文本；为 null 时返回 null，非数字或超出 long 范围为 400。
     */
    static Long parseOptional(String raw) {
        if (raw == null) {
            return null;
        }
        return parse(raw);
    }

    private static long parse(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("BOOT_COUNT_OUT_OF_RANGE",
                    "启动计数须为 long 范围内的十进制整数: " + raw);
        }
    }
}
