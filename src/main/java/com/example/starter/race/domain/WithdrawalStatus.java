package com.example.starter.race.domain;

/**
 * 退赛登记状态：
 * DNS-未出发（登记时无任何分段记录与完赛计时）；
 * DNF-中途退赛（登记时已有至少一条分段记录、尚无完赛计时）。
 */
public enum WithdrawalStatus {
    DNS,
    DNF
}
