package com.example.starter.evidence.dto;

/**
 * 证物摘要状态（查询时刻计算，不落库）：
 * NO_DIGEST 尚未封存登记摘要；
 * PENDING_WITNESS 当前版本见证人不足两名，待见证；
 * EXPORTABLE 当前版本两名不同见证人齐备，且当前内容摘要与封存登记一致，可导出；
 * DIGEST_MISMATCH 当前内容重算摘要（或内容引用）与封存登记不一致（摘要异常），导出将返回 422。
 * 已失效版本通过版本视图 status=SUPERSEDED 表达，旧见证不随重新封存转移。
 */
public enum DigestState {
    NO_DIGEST,
    PENDING_WITNESS,
    EXPORTABLE,
    DIGEST_MISMATCH
}
