package com.example.starter.service.view;

import java.time.Instant;

/**
 * 隔离信息视图。
 *
 * @param reason 隔离原因
 * @param isolatedAt 隔离时刻，UTC
 */
public record IsolationView(String reason, Instant isolatedAt) {
}