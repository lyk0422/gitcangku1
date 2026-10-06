package com.example.starter.firmware.domain;

/**
 * PREPARE 回执结果：PREPARED 目标已暂存（不改设备当前版本）；
 * FAILED 准备失败（整组 ABORTED，已暂存成员也不切版本）。
 */
public enum PrepareResult {
    PREPARED,
    FAILED
}
