package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 标签规范化与校验工具。规范化规则：去除首尾空白并转小写；结果长度 1~64。
 * 所有标签语义错误统一返回 422。
 */
public final class Tags {

    public static final int MAX_LENGTH = 64;

    private Tags() {
    }

    /**
     * 规范化单个标签；空白、超长等不合法输入抛出 422 INVALID_TAG。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw ApiException.unprocessable("INVALID_TAG", "标签不能为 null");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw ApiException.unprocessable("INVALID_TAG", "标签去空白后不能为空: [" + raw + "]");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw ApiException.unprocessable("INVALID_TAG",
                    "标签长度超过 " + MAX_LENGTH + ": [" + normalized + "]");
        }
        return normalized;
    }

    /**
     * 规范化标签集合：null 视为空集合；重复标签（规范化后）抛出 422 DUPLICATE_TAG。
     * 返回去重后按字典序升序的不可变列表，保证指纹与存储的稳定排序。
     */
    public static List<String> normalizeAll(List<String> rawTags) {
        if (rawTags == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String raw : rawTags) {
            String normalized = normalize(raw);
            if (!sorted.add(normalized)) {
                throw ApiException.unprocessable("DUPLICATE_TAG", "标签重复: " + normalized);
            }
        }
        return List.copyOf(sorted);
    }
}
