package com.example.starter.service;

import java.time.Instant;

/**
 * 对账校验发现的问题刻度。
 *
 * @param tickUtc 问题刻度起点（UTC）；指令级问题（如未确认）为 null
 * @param code    问题代码：MISSING_SAMPLE 缺采样 / EXCEEDS_CAP 超上限 / NOT_CONFIRMED 指令未确认 / WINDOW_NOT_FINISHED 区间未结束
 * @param message 问题描述
 */
public record ReconcileIssue(Instant tickUtc, String code, String message) {
}