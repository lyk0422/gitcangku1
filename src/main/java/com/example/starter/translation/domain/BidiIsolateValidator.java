package com.example.starter.translation.domain;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 译文双向文字隔离符校验器：只核验 Unicode 码点序列，不实现排版引擎。
 * 规则：U+2066（LRI）、U+2067（RLI）、U+2068（FSI）为隔离起始符，入栈；
 * U+2069（PDI）为隔离结束符，弹栈；最大嵌套深度 4；文本结束时栈必须为空；
 * 禁止 U+202A~U+202E 双向格式符与孤立的 U+2069。
 * 其他字符（含未列出的控制符）按原文保留，不删除、不自动修复。
 * 错误索引为从 0 起的 Unicode 码点索引（非 UTF-16 字符位置）。
 */
public final class BidiIsolateValidator {

    /** 隔离符最大嵌套深度。 */
    public static final int MAX_NESTING = 4;

    private static final int LRI = 0x2066;
    private static final int RLI = 0x2067;
    private static final int FSI = 0x2068;
    private static final int PDI = 0x2069;
    private static final int EMBEDDING_MIN = 0x202A;
    private static final int EMBEDDING_MAX = 0x202E;

    private BidiIsolateValidator() {
    }

    /**
     * 校验失败详情。
     *
     * @param codePointIndex 出错位置，从 0 起的 Unicode 码点索引
     * @param reason         失败原因描述
     */
    public record BidiError(int codePointIndex, String reason) {
    }

    /**
     * 校验文本的双向隔离符结构。
     *
     * @param text 待校验文本（非 null）
     * @return 合法返回 null；非法返回首个错误及其码点索引
     */
    public static BidiError validate(String text) {
        // 栈中存放入栈起始符的码点索引，用于未闭合时定位
        Deque<Integer> openStack = new ArrayDeque<>();
        int codePointIndex = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (codePoint >= EMBEDDING_MIN && codePoint <= EMBEDDING_MAX) {
                return new BidiError(codePointIndex,
                        "禁止的双向格式符 U+" + String.format("%04X", codePoint));
            }
            if (codePoint == LRI || codePoint == RLI || codePoint == FSI) {
                if (openStack.size() >= MAX_NESTING) {
                    return new BidiError(codePointIndex, "隔离符嵌套超过最大深度 " + MAX_NESTING);
                }
                openStack.push(codePointIndex);
            } else if (codePoint == PDI) {
                if (openStack.isEmpty()) {
                    return new BidiError(codePointIndex, "孤立的隔离结束符 U+2069");
                }
                openStack.pop();
            }
            offset += Character.charCount(codePoint);
            codePointIndex++;
        }
        if (!openStack.isEmpty()) {
            return new BidiError(openStack.peek(), "隔离起始符未闭合，文本结束时栈非空");
        }
        return null;
    }
}
