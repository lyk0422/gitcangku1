package com.example.starter.domain;

/** 应急走廊状态：ACTIVE 有效参与占用裁决；REVOKED 已撤销，仅影响后续占用。 */
public enum CorridorStatus {
    /** 有效：覆盖时空只允许应急占用，常规占用命中返回 409。 */
    ACTIVE,
    /** 已撤销：不再约束后续占用；已让渡占用不自动恢复。 */
    REVOKED
}
