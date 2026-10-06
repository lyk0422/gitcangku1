package com.example.starter.firmware.domain;

/**
 * 组准备回执结果：PREPARED 目标已暂存（不切版本）；FAILED 准备失败（整组中止）。
 * 不接受 SUCCESS：组设备必须经预备屏障统一激活，普通 SUCCESS 回执被拒。
 */
public enum PrepareResult {
    PREPARED,
    FAILED
}
