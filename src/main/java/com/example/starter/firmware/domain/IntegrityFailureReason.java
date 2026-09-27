package com.example.starter.firmware.domain;

/**
 * 完整性失败原因码，可区分且一旦固化不可改写。
 * OUT_OF_RANGE：分片序号越界（小于0或不小于要求分片数）；
 * DUPLICATE_SHARD：批量最终集合内出现重复序号；
 * MISSING_SHARD：最终集合存在缺口或数量不足（实际值少于要求值）；
 * DIGEST_MISMATCH：摘要格式非法或与登记摘要不一致；
 * AGGREGATE_MISMATCH：各分片摘要均匹配但聚合摘要与完整包摘要不一致。
 */
public enum IntegrityFailureReason {
    OUT_OF_RANGE,
    DUPLICATE_SHARD,
    MISSING_SHARD,
    DIGEST_MISMATCH,
    AGGREGATE_MISMATCH
}
