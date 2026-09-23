package com.example.starter.exposure.domain;

/**
 * 快照项决议状态。
 * <ul>
 *     <li>PENDING：冻结时为在途预占，尚未得到终态决议；</li>
 *     <li>CONFIRMED：合法回执确认，继续占用原频控；</li>
 *     <li>REJECTED：非法回执、到期释放或结算判定无法合法确认，原预占额度已释放。</li>
 * </ul>
 */
public enum ItemDecision {
    PENDING,
    CONFIRMED,
    REJECTED
}
