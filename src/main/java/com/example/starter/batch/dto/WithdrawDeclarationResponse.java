package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;

/**
 * 撤回声明响应。withdrawnVersions 为本次实际撤回的 ACTIVE 版本数；withdrawnAt 为 UTC instant。
 */
public record WithdrawDeclarationResponse(
        String declarationKey,
        int withdrawnVersions,
        DeclarationStatus status,
        Instant withdrawnAt
) {
}
