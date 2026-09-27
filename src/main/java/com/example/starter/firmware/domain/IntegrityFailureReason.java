package com.example.starter.firmware.domain;

/**
 * 完整性失败原因：INDEX_OUT_OF_RANGE 序号超出清单范围；DIGEST_MISMATCH 分片摘要与清单不匹配；
 * DUPLICATE_INDEX 同一请求内序号重复；INCOMPLETE_SET 最终集合缺失分片；AGGREGATE_MISMATCH 聚合摘要不匹配。
 */
public enum IntegrityFailureReason {
    INDEX_OUT_OF_RANGE,
    DIGEST_MISMATCH,
    DUPLICATE_INDEX,
    INCOMPLETE_SET,
    AGGREGATE_MISMATCH
}
