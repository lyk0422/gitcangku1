package com.example.starter.consent;

/**
 * 共同研究记录提案状态：PROPOSED 待双确认；FROZEN 当前版本已双确认冻结。
 * 内容修订后回到 PROPOSED 并清空旧确认，需双方重新确认。
 */
public enum JointStatus {
    PROPOSED,
    FROZEN
}
