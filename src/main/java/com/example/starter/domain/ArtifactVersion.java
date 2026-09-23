package com.example.starter.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn     true 表示该版本已撤回
 * @param contentDigest 登记时冻结的制品内容 SHA-256 摘要（64 位十六进制）
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        String contentDigest,
        List<DependencyRange> dependencies) {

    /** 兼容无显式摘要的场景：按规范化制品清单推导内容摘要。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, canonicalDigest(name, version, dependencies), dependencies);
    }

    /** 按规范化制品清单（名称/版本/依赖区间）推导稳定 SHA-256 摘要。 */
    public static String canonicalDigest(String name, int version, List<DependencyRange> dependencies) {
        StringBuilder canonical = new StringBuilder(name).append('@').append(version);
        if (dependencies != null) {
            dependencies.stream()
                    .map(d -> d.name() + ":" + d.minimumVersion() + ":" + d.maximumVersion())
                    .sorted()
                    .forEach(d -> canonical.append('|').append(d));
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
