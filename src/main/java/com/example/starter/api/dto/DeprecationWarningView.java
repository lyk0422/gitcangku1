package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定时刻命中的弃用警告（窗口尚未生效：resolvedAt &lt; effectiveAt，左闭右开）。
 *
 * <p>内容为锁定时刻的快照：之后调整窗口、撤销窗口或撤回替代版本均不改写。
 *
 * @param name               闭包内被弃用的制品名称
 * @param version            闭包内被弃用的精确版本号
 * @param replacementName    锁定时刻登记的替代制品名称
 * @param replacementVersion 锁定时刻登记的替代版本号（即使之后撤回也保留）
 * @param effectiveAt        锁定时刻窗口的弃用生效 UTC 时刻
 * @param note               弃用说明快照；null 表示当时无说明
 * @param resolvedAt         锁的 UTC 解析时刻
 */
public record DeprecationWarningView(
        String name,
        int version,
        String replacementName,
        int replacementVersion,
        Instant effectiveAt,
        String note,
        Instant resolvedAt) {
}
