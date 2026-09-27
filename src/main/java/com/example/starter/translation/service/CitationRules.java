package com.example.starter.translation.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 引文锚点的无状态领域规则：引用标识规范化、Unicode 码点区间截取与区间关系判定。
 * 区间一律采用码点偏移、左闭右开 [start, end)，避免代理对字符的 char 偏移歧义。
 */
public final class CitationRules {

    private CitationRules() {
    }

    /**
     * 规范化引用标识：先做 NFKC 兼容分解，删除全部 Unicode 空白，再按根区域转小写。
     * 例如 " ISBN 978-4 " 与 "isbn978-4" 视为同一标识。
     */
    public static String normalizeKey(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /** 文本的 Unicode 码点长度。 */
    public static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * 按码点偏移截取左闭右开区间 [start, end) 内的文本；
     * 调用方须先保证 0 &lt;= start &lt;= end &lt;= 码点长度。
     */
    public static String slice(String text, int start, int end) {
        int charStart = text.offsetByCodePoints(0, start);
        int charEnd = text.offsetByCodePoints(0, end);
        return text.substring(charStart, charEnd);
    }

    /** 区间是否为合法非空区间且未超出内容码点长度。 */
    public static boolean isValidRange(int start, int end, int contentLength) {
        return 0 <= start && start < end && end <= contentLength;
    }

    /** 两个左闭右开区间是否交叉：端点相接（a.end == b.start）不算交叉。 */
    public static boolean crosses(int startA, int endA, int startB, int endB) {
        return startA < endB && startB < endA;
    }
}
