package com.example.starter.exposure.domain;

/**
 * 访客曝光抑制区间状态。
 * <ul>
 *     <li>ACTIVE：生效中。当前时刻落在 [validFromUtc, validUntilUtc) 内即命中抑制；
 *     已被提前结束但结束时刻未到的区间仍为 ACTIVE（validUntilUtc 已缩短）；</li>
 *     <li>EARLY_ENDED：已提前结束且结束时刻已过，记录不可变，仅作历史保留；</li>
 *     <li>DELETED：未开始即被删除，立即失效；保留不可变删除记录。</li>
 * </ul>
 */
public enum SuppressionIntervalStatus {
    ACTIVE,
    EARLY_ENDED,
    DELETED
}
