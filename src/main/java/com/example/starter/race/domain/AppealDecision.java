package com.example.starter.race.domain;

/**
 * 裁决建议类型。
 * UPHOLD-维持处罚；REMOVE-撤销处罚；REPLACE-以非负替代罚时生成新处罚版本。
 */
public enum AppealDecision {
    UPHOLD,
    REMOVE,
    REPLACE
}
