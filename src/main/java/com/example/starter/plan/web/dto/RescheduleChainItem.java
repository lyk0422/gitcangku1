package com.example.starter.plan.web.dto;

import java.time.LocalDate;

/**
 * 改签链节点：链上某一计划的摘要，按从最前驱到最后继的顺序排列。
 */
public record RescheduleChainItem(String scheduleKey, LocalDate opDate, int version, String status) {
}
