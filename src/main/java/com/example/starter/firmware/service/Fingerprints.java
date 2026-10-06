package com.example.starter.firmware.service;

/**
 * 幂等指纹构造：长度前缀结构化编码，按字段逐一比较语义，
 * 避免分隔符碰撞（如 ["a|b"] 与 ["a", "b"] 拼接后相同的问题）。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 将操作、操作者、资源与全部参数编码为单个指纹串；null 与空串编码不同。
     */
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
