package com.example.starter.evidence;

/**
 * 交接类型。
 * REGULAR 普通交接（无检查点）；REGULATED 受监管交接（声明有序检查点，运送人依次签到）。
 */
public enum TransferType {
    REGULAR,
    REGULATED
}