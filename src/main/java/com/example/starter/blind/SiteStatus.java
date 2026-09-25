package com.example.starter.blind;

/**
 * 试验中心状态。
 * 状态机：INACTIVE 首次双人确认激活为 ACTIVE；ACTIVE 可暂停为 SUSPENDED；
 * SUSPENDED 须再次双人确认恢复为 ACTIVE（新代次）；任意非关闭状态可关闭为 CLOSED；CLOSED 不可恢复。
 */
public enum SiteStatus {
    /** 未激活：不得生成盲码或分配区组。 */
    INACTIVE,
    /** 已激活：接受新分配。 */
    ACTIVE,
    /** 已暂停：拒绝新分配，但既有盲态、区组容量与揭盲权限不变。 */
    SUSPENDED,
    /** 已关闭：不可恢复，拒绝新分配。 */
    CLOSED
}
