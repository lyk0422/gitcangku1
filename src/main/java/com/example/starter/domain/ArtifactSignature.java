package com.example.starter.domain;

import java.time.Instant;

/**
 * 制品版本追加签名的不可变快照。
 *
 * @param artifactId 被签名制品版本 ID
 * @param keyId      签名钥匙标识
 * @param digest     签名声明的内容摘要（SHA-256 十六进制小写）
 * @param createdAt  签名追加时间（UTC）
 */
public record ArtifactSignature(
        long artifactId,
        String keyId,
        String digest,
        Instant createdAt) {
}
