package com.example.starter.evidence;

/**
 * 证物数字摘要见证状态。
 * NOT_SEALED 尚未封存登记内容；
 * PENDING_WITNESS 待见证：已封存且摘要匹配，但当前版本不同保管人见证不足两名；
 * EXPORTABLE 可导出：当前内容摘要与最新封存版本一致且两名不同保管人见证齐备；
 * DIGEST_MISMATCH 摘要异常：当前内容重算摘要与最新封存版本登记值不符（文件被改动）。
 */
public enum DigestState {
    NOT_SEALED,
    PENDING_WITNESS,
    EXPORTABLE,
    DIGEST_MISMATCH
}
