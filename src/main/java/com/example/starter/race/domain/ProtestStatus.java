package com.example.starter.race.domain;

/**
 * 申诉状态：PENDING-待裁决（暂缓封榜）；ACCEPTED-申诉成立，保证金全额退回；
 * REJECTED-申诉驳回，保证金全部没收。ACCEPTED/REJECTED 均为终态，不可重复裁决或改判。
 */
public enum ProtestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
