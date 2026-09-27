package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊关联诊断只读视图：走廊当前状态、不可变事件、让渡历史，
 * 可进一步经占用/审查标识关联原占用与处置结果。
 *
 * @param corridorId 走廊标识
 * @param status     ACTIVE / REVOKED
 * @param version    当前走廊版本
 * @param startMs    有效期起点（含），epoch 毫秒 UTC
 * @param endMs      有效期终点（不含），epoch 毫秒 UTC
 * @param altMin     高度下界（含），米
 * @param altMax     高度上界（不含），米
 * @param priority   优先级
 * @param revokeReason 撤销原因；null 未撤销
 * @param revokedMs  撤销时刻；null 未撤销
 * @param createdMs  建立时刻
 * @param events     不可变事件列表（按 seq 升序）
 * @param yields     让渡历史列表（按 seq 升序）
 */
public record CorridorDetailView(String corridorId, String status, int version,
                                 long startMs, long endMs, int altMin, int altMax, int priority,
                                 String revokeReason, Long revokedMs, long createdMs,
                                 List<CorridorEventView> events,
                                 List<YieldHistoryView> yields) {
}
