package com.example.starter.evidence;

/**
 * 证物介质状态。
 * ACTIVE 当前读取来源（每件证物恰好一个）；UNVERIFIED 新副本已收到合成内容、待见证验证；
 * VERIFIED 摘要与源一致、待保管人显式切换；RETIRED 已作废（仅保留历史元数据，不可作为当前读取来源）。
 */
public enum MediaStatus {
    ACTIVE,
    UNVERIFIED,
    VERIFIED,
    RETIRED
}
