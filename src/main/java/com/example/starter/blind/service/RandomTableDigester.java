package com.example.starter.blind.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 随机化表摘要：SHA-256 over 规范文本，覆盖版本身份、容量、处理代码集合与完整序列顺序。
 * 封存记录只保存该摘要，不保存序列本身；序列以库内席位为准，摘要用于一致性校验。
 */
public final class RandomTableDigester {

    private RandomTableDigester() {
    }

    /**
     * 计算随机表摘要；同一身份、容量、代码集合与序列顺序必然得到相同摘要。
     *
     * @param experimentId 实验编号
     * @param blockNo      区组号
     * @param versionNo    随机表版本号
     * @param capacity     版本容量（序列条数）
     * @param codes        处理代码集合（调用方负责排序去重）
     * @param sequence     完整处理代码序列，按区内序号顺序
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String digest(String experimentId, int blockNo, int versionNo, int capacity,
                                String codes, String sequence) {
        String canonical = "experimentId=" + experimentId + '\n'
                + "blockNo=" + blockNo + '\n'
                + "versionNo=" + versionNo + '\n'
                + "capacity=" + capacity + '\n'
                + "codes=" + codes + '\n'
                + "sequence=" + sequence + '\n';
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
