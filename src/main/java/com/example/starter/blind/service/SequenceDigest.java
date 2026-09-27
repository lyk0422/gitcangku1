package com.example.starter.blind.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 随机表摘要算法：封存时由研究侧提交、服务端用相同规则复核。
 * 摘要绑定 sealKey、实验、区组、版本号、容量、处理代码集合与完整序列，
 * 但只输出 SHA-256 十六进制摘要，不暴露具体序列。
 */
public final class SequenceDigest {

    private SequenceDigest() {
    }

    /**
     * 计算随机表摘要。
     *
     * @param sealKey        封存密钥
     * @param experimentId   实验编号
     * @param blockNo        区组号
     * @param versionNo      随机表版本号
     * @param capacity       区组容量（序号总数）
     * @param treatmentCodes 处理代码集合（升序逗号分隔，如 A,B）
     * @param sequence       版本内序号 1..capacity 对应的处理代码序列，长度必须等于容量
     * @return 64 位小写十六进制 SHA-256 摘要
     */
    public static String compute(String sealKey, String experimentId, int blockNo, int versionNo,
                                 int capacity, String treatmentCodes, List<String> sequence) {
        if (sequence == null || sequence.size() != capacity) {
            throw new IllegalArgumentException("序列长度必须与区组容量一致");
        }
        String raw = String.join("|",
                sealKey,
                experimentId,
                Integer.toString(blockNo),
                Integer.toString(versionNo),
                Integer.toString(capacity),
                treatmentCodes,
                String.join("", sequence));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
