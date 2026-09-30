package com.example.starter.film.domain;

/**
 * 母版状态；审核通过后为 {@link #APPROVED}，撤销后为 {@link #REVOKED}。
 * 撤销只阻止新的交付，已生成的交付回执与母版快照不受影响。
 */
public enum MasterStatus {

    /** 审核通过，可被交付引用。 */
    APPROVED,

    /** 已撤销，不允许新的交付。 */
    REVOKED
}
