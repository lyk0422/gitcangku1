package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 锁定点的一次角色确认证据。
 *
 * @param role       维护角色：ELECTRICIAN 电气 / MECHANIC 机械
 * @param operator   确认操作人
 * @param confirmedAt 确认时刻（UTC）
 */
public record LockConfirmation(String role, String operator, Instant confirmedAt) {

    public static final String ELECTRICIAN = "ELECTRICIAN";
    public static final String MECHANIC = "MECHANIC";
}
