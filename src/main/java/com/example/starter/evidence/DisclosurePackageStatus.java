package com.example.starter.evidence;

/**
 * 披露包状态。
 * FROZEN 已冻结待交付（可继续被修订或撤销）；DELIVERED 已交付（不可撤销，只能出更高修订包）；
 * REVOKED 已撤销（仅 FROZEN 可撤销，撤销后只读）。
 */
public enum DisclosurePackageStatus {
    FROZEN,
    DELIVERED,
    REVOKED
}
