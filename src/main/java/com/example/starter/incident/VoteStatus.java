package com.example.starter.incident;

/**
 * 投票记录状态：CAST 有效票（计入法定人数）；WITHDRAWN 已撤回；
 * STALE 已失效（任务修订后版本不匹配）。仅 CAST 参与计数。
 */
public enum VoteStatus {
    CAST,
    WITHDRAWN,
    STALE
}
