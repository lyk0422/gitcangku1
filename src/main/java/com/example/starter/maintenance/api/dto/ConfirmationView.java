package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 锁定点角色确认视图。
 *
 * @param role         维护角色：ELECTRICIAN 电气 / MECHANIC 机械
 * @param operator     确认操作人
 * @param confirmedAt  确认时刻（UTC）
 */
public record ConfirmationView(String role, String operator, Instant confirmedAt) {
}
