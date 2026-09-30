package com.example.starter.evidence;

/**
 * 冻结在查询时刻的有效状态（查询时按 UTC 时钟即时计算，不写库）。
 * ACTIVE 生效中（未解除且未到期）；RELEASED 已由保管人解除；EXPIRED 已自然到期。
 */
public enum HoldState {
    ACTIVE,
    RELEASED,
    EXPIRED
}
