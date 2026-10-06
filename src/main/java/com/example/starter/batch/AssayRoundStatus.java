package com.example.starter.batch;

/**
 * 平行测定轮次状态。
 * OPEN 测定中，可读数 0～2 条；读数齐全（3 条）后立即封结：
 * 均值落入闭区间且最大减最小不超过最大极差为 SEALED_PASS，否则 SEALED_FAIL。
 * 封结为终态，读数不可覆写；SEALED_FAIL 或复测需要可开新轮次，每项最多 3 轮。
 */
public enum AssayRoundStatus {
    OPEN,
    SEALED_PASS,
    SEALED_FAIL
}
