package com.example.starter.film.model;

/**
 * 整片审核请求状态。
 * <ul>
 *     <li>{@link #PENDING}：已受理，已按序号固定每段版次与摘要基线，尚未通过；</li>
 *     <li>{@link #APPROVED}：审核通过，已生成不可变母版；</li>
 *     <li>{@link #EXPIRED}：审核前任一片段出现新提交，基线过期，或通过时发现版本冲突；</li>
 *     <li>{@link #REVOKED}：审核通过后被撤销，只阻止新的交付，不影响既有回执。</li>
 * </ul>
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    EXPIRED,
    REVOKED
}
