package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定时刻命中的尚未生效弃用警告（固化在锁文件上的历史证据）。
 *
 * @param secondsUntilEffective 解析时刻距生效时刻的实际秒数差额（正数），调用方无需自行计算
 */
public record LockDeprecationWarningView(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String note,
        long secondsUntilEffective) {
}
