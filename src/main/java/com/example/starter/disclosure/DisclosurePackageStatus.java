package com.example.starter.disclosure;

/**
 * 披露包状态。
 * GENERATED 已生成未交付，可撤销或增删生成修订；
 * DELIVERED 已交付，不可撤销，发现问题只能创建更高修订包；
 * REVOKED 已撤销（仅未交付包可撤销）。
 */
public enum DisclosurePackageStatus {
    GENERATED,
    DELIVERED,
    REVOKED
}
