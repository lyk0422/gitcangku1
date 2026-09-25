package com.example.starter.evidence;

/**
 * 保全冻结状态。
 * ACTIVE 未解除（是否“有效”还须结合 UTC 区间：effective_at &lt;= now &lt; expire_at）；
 * RELEASED 已解除（终态，不可再变，历史保留）。
 */
public enum HoldStatus {
    ACTIVE,
    RELEASED
}
