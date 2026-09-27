package com.example.starter.incident;

/**
 * 投票记录状态：
 * CAST 为当前版本有效票；任务修订后旧版本票转 STALE，永久保留且不计入新版本；
 * 投票人撤回后转 WITHDRAWN，永久保留、不可恢复，撤回后可在新版本内重新投。
 */
public enum VoteStatus {

    /** 当前版本有效票，计入法定人数。 */
    CAST,

    /** 任务版本变化后失效的旧票，永久保留，不计入新版本。 */
    STALE,

    /** 投票人主动撤回的票，永久保留，不计入法定人数。 */
    WITHDRAWN;
}
