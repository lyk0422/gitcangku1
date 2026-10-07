package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 放行门禁诊断视图（只读，不改变状态）。
 * boundDeclarationCount 为已绑定声明数，validDeclarationCount 为当前时点可用于放行的
 * 有效声明数，requiredValidDeclarationCount 为放行要求的有效声明数（恒为 1），
 * missingValidDeclarationCount 为差额；releasable 为门禁是否通过。
 */
public record ReleaseCheckResponse(
        String batchKey,
        BatchStatus status,
        int boundDeclarationCount,
        int validDeclarationCount,
        int requiredValidDeclarationCount,
        int missingValidDeclarationCount,
        boolean releasable
) {
}
