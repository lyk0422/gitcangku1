package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * 公开的点分数字模块版本：由 1~16 段非负整数组成，以 '.' 分隔（如 1、1.2、10.3.1）。
 * 比较时逐段按数值比较，公共前缀段相等时段数多者更高（1.2.0 &gt; 1.2）。
 * 格式非法由 {@link #parse(String, String)} 抛出 422（UNPROCESSABLE_ENTITY）。
 */
public final class ModuleVersion implements Comparable<ModuleVersion> {

    private final String raw;
    private final int[] segments;

    private ModuleVersion(String raw, int[] segments) {
        this.raw = raw;
        this.segments = segments;
    }

    public String raw() {
        return raw;
    }

    /**
     * 解析点分数字版本。
     *
     * @param value  版本字符串
     * @param field  触发解析的字段描述，用于错误信息
     */
    public static ModuleVersion parse(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw ApiException.unprocessable("INVALID_MODULE_VERSION", field + " 版本不能为空");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length > 16) {
            throw ApiException.unprocessable("INVALID_MODULE_VERSION",
                    field + " 版本段数超过 16: " + value);
        }
        List<Integer> numbers = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 9) {
                throw ApiException.unprocessable("INVALID_MODULE_VERSION",
                        field + " 版本格式非法（须为点分非负整数）: " + value);
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    throw ApiException.unprocessable("INVALID_MODULE_VERSION",
                            field + " 版本格式非法（须为点分非负整数）: " + value);
                }
            }
            numbers.add(Integer.parseInt(part));
        }
        int[] segments = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            segments[i] = numbers.get(i);
        }
        return new ModuleVersion(value, segments);
    }

    @Override
    public int compareTo(ModuleVersion other) {
        int n = Math.max(segments.length, other.segments.length);
        for (int i = 0; i < n; i++) {
            int a = i < segments.length ? segments[i] : 0;
            int b = i < other.segments.length ? other.segments[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return raw;
    }
}
