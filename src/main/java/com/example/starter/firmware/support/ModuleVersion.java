package com.example.starter.firmware.support;

import com.example.starter.firmware.error.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * 公开的点分数字版本（如 1、1.2、10.2.33）。每段为非负整数，按段从左到右数值比较，缺失段视为 0。
 * 格式非法（空串、非数字、空段、多余分隔符、前导零、段数或位数超限）统一抛 422。
 */
public final class ModuleVersion implements Comparable<ModuleVersion> {

    private static final int MAX_SEGMENTS = 16;
    private static final int MAX_DIGITS = 18;
    private static final java.util.regex.Pattern SEGMENT = java.util.regex.Pattern.compile("0|[1-9][0-9]*");

    private final String raw;
    private final List<Long> segments;

    private ModuleVersion(String raw, List<Long> segments) {
        this.raw = raw;
        this.segments = segments;
    }

    /**
     * 解析点分数字版本；非法格式返回 422（INVALID_VERSION_FORMAT）。
     */
    public static ModuleVersion parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalid(value);
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length == 0 || parts.length > MAX_SEGMENTS) {
            throw invalid(value);
        }
        List<Long> numbers = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.length() > MAX_DIGITS || !SEGMENT.matcher(part).matches()) {
                throw invalid(value);
            }
            numbers.add(Long.parseLong(part));
        }
        return new ModuleVersion(value, List.copyOf(numbers));
    }

    private static ApiException invalid(String value) {
        return ApiException.unprocessable("INVALID_VERSION_FORMAT",
                "版本必须为点分非负整数（如 1.2.3），非法版本: " + value);
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(ModuleVersion other) {
        int count = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < count; i++) {
            long left = i < segments.size() ? segments.get(i) : 0L;
            long right = i < other.segments.size() ? other.segments.get(i) : 0L;
            int cmp = Long.compare(left, right);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * 不宽松：仅用于登记时比较两个已解析版本的大小关系（取多路径约束最大值）。
     */
    public boolean isLowerThan(ModuleVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModuleVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        int end = segments.size();
        while (end > 0 && segments.get(end - 1) == 0L) {
            end--;
        }
        return segments.subList(0, end).hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
