package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 切换单视图：封锁区段、左闭右开 UTC 窗口与当前状态。
 */
public record SwitchView(String switchKey, String sectionId, Instant startUtc, Instant endUtc,
                         String status) {
}
