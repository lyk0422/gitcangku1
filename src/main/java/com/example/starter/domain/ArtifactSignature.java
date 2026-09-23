package com.example.starter.domain;

import java.time.Instant;

/**
 * 制品版本上的一条追加签名。
 *
 * @param artifactId 被签名制品版本 ID
 * @param keyId      签名钥匙 ID
 * @param digest     签名携带的内容 SHA-256 摘要（64 位十六进制）
 * @param createdAt  补签时刻（UTC）
 */
public record ArtifactSignature(
        long artifactId,
        String keyId,
        String digest,
        Instant createdAt) {
}
