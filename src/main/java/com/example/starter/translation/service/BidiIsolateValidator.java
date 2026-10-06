package com.example.starter.translation.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 双向文字隔离符校验器：只核验 Unicode 码点序列，不实现排版引擎。
 * 仅 U+2066（LRI）、U+2067（RLI）、U+2068（FSI）可作为隔离起始入栈，U+2069（PDI）弹栈；
 * 最大嵌套 4 层，文本结束时栈必须为空；禁止 U+202A~U+202E 嵌入/覆盖控制符与孤立的 U+2069。
 * 其他字符一律按原文接受：不删除控制符，也不把不合法文本自动修好。
 */
public final class BidiIsolateValidator {

    /** 最大隔离嵌套层数。 */
    public static final int MAX_NESTING = 4;

    private static final int LRI = 0x2066;
    private static final int RLI = 0x2067;
    private static final int FSI = 0x2068;
    private static final int PDI = 0x2069;
    private static final int EMBEDDING_START = 0x202A;
    private static final int EMBEDDING_END = 0x202E;

    private BidiIsolateValidator() {
    }

    /**
     * 校验文本的双向隔离符合法性，按码点遍历（补充平面字符计为一个码点，不按 UTF-16 位置计数）。
     *
     * @param text 待校验文本
     * @return 合法返回 -1；非法返回从 0 起的出错码点索引——嵌套超限、孤立结束符、
     *         禁用控制符为当前码点索引，结束未闭合为最早未闭合起始码点索引
     */
    public static int findErrorIndex(String text) {
        Deque<Integer> openStarts = new ArrayDeque<>();
        int codePointIndex = 0;
        int offset = 0;
        while (offset < text.length()) {
            int cp = text.codePointAt(offset);
            if (cp >= EMBEDDING_START && cp <= EMBEDDING_END) {
                return codePointIndex;
            }
            if (cp == LRI || cp == RLI || cp == FSI) {
                if (openStarts.size() >= MAX_NESTING) {
                    return codePointIndex;
                }
                openStarts.push(codePointIndex);
            } else if (cp == PDI) {
                if (openStarts.isEmpty()) {
                    return codePointIndex;
                }
                openStarts.pop();
            }
            offset += Character.charCount(cp);
            codePointIndex++;
        }
        if (!openStarts.isEmpty()) {
            return openStarts.peekLast();
        }
        return -1;
    }
}
