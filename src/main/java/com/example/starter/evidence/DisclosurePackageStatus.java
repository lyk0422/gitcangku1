package com.example.starter.evidence;

/**
 * 披露包状态机：FROZEN 已冻结未交付（可增删证物、可交付、可撤销）；
 * DELIVERED 已交付（不可撤销、不可增删，发现问题须创建更高修订包）；
 * REVOKED 已撤销（终态，不可再变更）。
 */
public enum DisclosurePackageStatus {
    FROZEN,
    DELIVERED,
    REVOKED
}
