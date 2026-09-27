package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：覆盖操作者、操作类型、规范化对象集合及所有影响结果的字段。
 * 各字段采用长度前缀编码（len:value）拼接，不依赖未转义分隔符，避免集合元素歧义。
 */
final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹：任一字段为 null 时按空串处理；返回 SHA-256 十六进制。
     */
    static String of(List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            canonical.append(value.length()).append(':').append(value);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
