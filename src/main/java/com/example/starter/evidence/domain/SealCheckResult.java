package com.example.starter.evidence.domain;

/**
 * 封条核验结果：PASS 通过（仅追加记录）；FAIL 失败（证物进入 SEAL_BROKEN）。
 */
public enum SealCheckResult {
    PASS,
    FAIL
}
