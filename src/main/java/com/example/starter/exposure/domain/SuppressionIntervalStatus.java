package com.example.starter.exposure.domain;

/**
 * 访客抑制区间状态。
 * <ul>
 *     <li>ACTIVE：区间有效，包含已生效与尚未开始两种情况；已开始的区间只能提前结束，
 *     未开始的区间可立即删除；</li>
 *     <li>DELETED：未开始即被删除，原区间立即失效，此行不可变，
 *     另在 suppression_delete_record 保留不可变删除记录。</li>
 * </ul>
 */
public enum SuppressionIntervalStatus {
    ACTIVE,
    DELETED
}
