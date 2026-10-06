package com.example.starter.incident;

/**
 * 资源租约状态机：ACTIVE → SUPERSEDED（被续期取代）、ACTIVE → RELEASED（任务完成/取消回收）
 * 或 ACTIVE → EXPIRED（到期回收）。非 ACTIVE 均为终态，历史版本行保留不改写。
 */
public enum LeaseStatus {

    /** 活动租约：授权区间 [assignedAt, expiresAt) 内有效。 */
    ACTIVE,

    /** 已被续期产生的新版本取代。 */
    SUPERSEDED,

    /** 任务完成或取消时正常回收。 */
    RELEASED,

    /** 授权到期回收（命令时按注入时钟判定并落库）。 */
    EXPIRED;
}
