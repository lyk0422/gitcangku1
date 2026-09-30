package com.example.starter.exposure.domain;

/**
 * 访客抑制名单状态。
 * <ul>
 *     <li>ACTIVE：抑制生效中，访客全部公告的新曝光预占一律返回 409；</li>
 *     <li>RELEASED：已解除，仅允许后续新申请，历史被取消预占不恢复。</li>
 * </ul>
 */
public enum SuppressionStatus {
    ACTIVE,
    RELEASED
}
