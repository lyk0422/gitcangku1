package com.example.starter.batch;

/**
 * 检验结论：PASS 通过；FAIL 不通过（任一 FAIL 立即使批次进入 REJECTED）。
 */
public enum TestOutcome {
    PASS,
    FAIL
}
