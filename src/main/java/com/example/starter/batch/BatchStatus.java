package com.example.starter.batch;

/**
 * 批次状态机：QUARANTINED（隔离中）→ PENDING_RELEASE（必做项全部 PASS，待放行）
 * → RELEASE_REVIEW（首个角色已批准）→ RELEASED（双角色批准完成，已放行）。
 * 任一检验 FAIL 立即进入 REJECTED（终态）；已放行批次可召回进入 RECALLED（终态）。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED
}
