package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定时刻固化的弃用警告快照（含替代建议），后续弃用变更不改写。
 *
 * @param replacementVersion 锁定时刻窗口登记的同制品替代版本号
 * @param effectiveAt 锁定时刻窗口登记的弃用生效时刻（UTC）
 * @param reason 锁定时刻窗口登记的弃用说明；null 表示未填写
 */
public record DeprecationWarningView(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String reason) {
}
