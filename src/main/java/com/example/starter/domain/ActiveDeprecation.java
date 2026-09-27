package com.example.starter.domain;

import java.time.Instant;

/**
 * 一条生效中的弃用窗口（状态为 REGISTERED）的不可变视图。
 *
 * @param name               被弃用制品名称
 * @param version            被弃用制品版本号
 * @param replacementVersion 替代版本号（与被弃用版本同制品）
 * @param effectiveAt       弃用生效 UTC 时刻；区间按左闭右开解释
 * @param note               弃用说明（永不为 null，无说明为空串）
 */
public record ActiveDeprecation(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String note) {
}
