package com.example.starter.race.domain;

/**
 * 第一裁决干事的申诉建议。
 *
 * <ul>
 *   <li>{@link #UPHOLD}：维持原处罚；</li>
 *   <li>{@link #REMOVE}：撤销原处罚；</li>
 *   <li>{@link #REPLACE}：改判为非负替代加时罚时（毫秒，允许0）。</li>
 * </ul>
 */
public enum AppealRecommendation {
    UPHOLD,
    REMOVE,
    REPLACE
}
