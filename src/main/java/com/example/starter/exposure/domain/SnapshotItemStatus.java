package com.example.starter.exposure.domain;

/**
 * 撤回快照项状态。
 * <ul>
 *     <li>SETTLING：已冻结待决议，对应预占仍为 RESERVED；</li>
 *     <li>CONFIRMED：回执满足截点与有效期规则，已确认并消耗原频控；</li>
 *     <li>REJECTED：回执不满足时间规则，或结算判定已无法合法确认，额度已释放；</li>
 *     <li>EXPIRED：到达到期时刻仍未确认，额度已释放。</li>
 * </ul>
 */
public enum SnapshotItemStatus {
    SETTLING,
    CONFIRMED,
    REJECTED,
    EXPIRED
}
