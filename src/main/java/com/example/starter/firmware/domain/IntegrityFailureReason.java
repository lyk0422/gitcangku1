package com.example.starter.firmware.domain;

/**
 * 分片完整性失败原因：CHUNK_MISSING 缺失；CHUNK_DUPLICATE 重复；
 * CHUNK_DIGEST_MISMATCH 分片摘要不匹配；PACKAGE_DIGEST_MISMATCH 聚合摘要不匹配。
 */
public enum IntegrityFailureReason {
    CHUNK_MISSING,
    CHUNK_DUPLICATE,
    CHUNK_DIGEST_MISMATCH,
    PACKAGE_DIGEST_MISMATCH
}
