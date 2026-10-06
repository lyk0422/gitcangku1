package com.example.starter.firmware.service;

/**
 * 结构化幂等指纹：对每段参数做长度前缀编码（"长度:内容"），null 段编码为 "-1:"。
 * 相比简单分隔符拼接，参数内容含任意字符都不会改变分段边界，避免分隔符碰撞。
 */
public final class StructuredFingerprint {

    private StructuredFingerprint() {
    }

    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                sb.append("-1:");
            } else {
                sb.append(part.length()).append(':').append(part);
            }
        }
        return sb.toString();
    }
}
