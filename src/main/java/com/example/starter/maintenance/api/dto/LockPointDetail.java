package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定点明细视图（稳定排序：按创建序号 ordinal 升序）。
 *
 * @param lockPointId        锁定点标识
 * @param ordinal            创建时提交序号（从 0 起）
 * @param name               锁定点名称/位置描述，可能为 null
 * @param requiredRoleCount  要求确认角色数（固定 2：两名不同维护角色）
 * @param confirmedRoleCount 已确认的不同角色数量（实际值，0~2）
 * @param missingRoleCount   尚缺确认的角色数量（差额 = 2 - 已确认）
 * @param released           该锁定点是否已解除
 * @param confirmations      确认明细（按确认时刻、确认 id 升序）
 */
public record LockPointDetail(
        String lockPointId,
        int ordinal,
        String name,
        int requiredRoleCount,
        int confirmedRoleCount,
        int missingRoleCount,
        boolean released,
        List<ConfirmationView> confirmations) {
}
