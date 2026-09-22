package com.example.starter.firmware.domain;

/**
 * 回执结果：SUCCESS 成功（更新设备当前版本）；FAILED 失败（本轮不再投放）。
 */
public enum ReceiptResult {
    SUCCESS,
    FAILED
}
