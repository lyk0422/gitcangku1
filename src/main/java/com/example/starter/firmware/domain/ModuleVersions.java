package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

/**
 * 公开的点分数字版本：一段或多段非负整数以点分隔（如 1、1.2、1.2.0）。
 * 比较按段数值进行，缺失段按 0 处理（1.2 与 1.2.0 相等）；段内前导零不影响数值。
 * 格式非法（空串、空段、非数字、负号等）抛出 422。
 */
public final class ModuleVersions {

    private ModuleVersions() {
    }

    /**
     * 校验版本格式，非法时抛出 422 INVALID_VERSION_FORMAT。
     */
    public static void validate(String version) {
        parse(version);
    }

    /**
     * 比较两个点分数字版本，返回负数、0 或正数。任一格式非法抛出 422。
     */
    public static int compare(String left, String right) {
        String[] a = parse(left);
        String[] b = parse(right);
        int segments = Math.max(a.length, b.length);
        for (int i = 0; i < segments; i++) {
            String sa = i < a.length ? a[i] : "0";
            String sb = i < b.length ? b[i] : "0";
            int cmp = compareNumeric(sa, sb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * 设备版本是否满足最低要求：deviceVersion >= minVersion。
     */
    public static boolean satisfies(String deviceVersion, String minVersion) {
        return compare(deviceVersion, minVersion) >= 0;
    }

    private static String[] parse(String version) {
        if (version == null || version.isEmpty()) {
            throw invalid(version);
        }
        String[] segments = version.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
                throw invalid(version);
            }
        }
        return segments;
    }

    private static int compareNumeric(String a, String b) {
        String na = stripLeadingZeros(a);
        String nb = stripLeadingZeros(b);
        if (na.length() != nb.length()) {
            return na.length() < nb.length() ? -1 : 1;
        }
        return na.compareTo(nb);
    }

    private static String stripLeadingZeros(String segment) {
        int i = 0;
        while (i < segment.length() - 1 && segment.charAt(i) == '0') {
            i++;
        }
        return segment.substring(i);
    }

    private static ApiException invalid(String version) {
        return ApiException.unprocessableEntity("INVALID_VERSION_FORMAT",
                "模块版本必须是点分数字格式（如1.2.0）: " + version);
    }
}
