package com.example.starter.evidence;

/**
 * 证物存储介质状态。
 * ACTIVE 当前读取来源（每件证物恰好一个）；UNVERIFIED 新介质已收到合成内容但摘要未见证；
 * VERIFIED 摘要经非保管人见证者确认等于源摘要，等待保管人切换；RETIRED 已被新介质作废，
 * 不可作为当前读取来源，历史元数据保留。
 */
public enum MediaStatus {
    ACTIVE,
    UNVERIFIED,
    VERIFIED,
    RETIRED
}
