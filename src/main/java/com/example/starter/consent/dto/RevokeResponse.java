package com.example.starter.consent.dto;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 撤回响应：在原有授权状态基础上报告被生效冻结保留的记录数。
 *
 * @param subjectKey       主体标识
 * @param purpose          用途
 * @param epoch            被撤回的授权代次
 * @param status           撤回后状态，固定 REVOKED
 * @param retainedRecords  撤回时存在生效冻结而不得物理清除的记录数
 */
public record RevokeResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        GrantStatus status,
        int retainedRecords) {
}
