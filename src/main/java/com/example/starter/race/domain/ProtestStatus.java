package com.example.starter.race.domain;

/**
 * 申诉状态：
 * <ul>
 *   <li>PENDING-待裁决：保证金已冻结，相关成绩暂缓封榜；</li>
 *   <li>ACCEPTED-申诉成立：终态，保证金已全额退回；</li>
 *   <li>REJECTED-申诉驳回：终态，保证金已全部没收。</li>
 * </ul>
 */
public enum ProtestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
