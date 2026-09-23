package com.example.starter.domain;

import java.time.Instant;

/**
 * 可信钥匙的快照（随首次引用它的策略发布而登记）。
 *
 * @param keyId     钥匙 ID
 * @param createdAt 首次随策略发布的时间（UTC）
 * @param revokedAt 撤销时刻（UTC），null 表示未撤销
 */
public record TrustedKey(String keyId, Instant createdAt, Instant revokedAt) {

    /** 是否已撤销。 */
    public boolean revoked() {
        return revokedAt != null;
    }
}
