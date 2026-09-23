package com.example.starter.exposure.domain;

/**
 * 公告版本撤回状态。
 * <ul>
 *     <li>SETTLING：快照已形成，仍有 PENDING 快照项等待回执或显式结算；</li>
 *     <li>COMPLETED：所有快照项均已进入终态（CONFIRMED/REJECTED）。</li>
 * </ul>
 */
public enum WithdrawalStatus {
    SETTLING,
    COMPLETED
}
