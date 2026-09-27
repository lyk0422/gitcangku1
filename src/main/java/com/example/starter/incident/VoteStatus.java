package com.example.starter.incident;

/**
 * 决策投票生命周期：CAST → WITHDRAWN（投票人撤回）或 CAST → STALE（任务版本变化失效）。
 * 仅 CAST 计入法定人数；WITHDRAWN 与 STALE 保留为历史证据，不物理删除。
 */
public enum VoteStatus {

    /** 有效票。 */
    CAST,

    /** 已撤回：投票人主动撤回，不再计入。 */
    WITHDRAWN,

    /** 已失效：提案涉及的任务版本变化后旧票失效，不计入新版本。 */
    STALE;
}
