package com.example.starter.exposure.domain;

/**
 * 公告版本撤回单状态。
 * <ul>
 *     <li>SETTLING：快照已冻结，等待快照项全部进入终态；</li>
 *     <li>COMPLETED：快照内所有预占均已进入终态，撤回完成。</li>
 * </ul>
 */
public enum WithdrawalStatus {
    SETTLING,
    COMPLETED
}
