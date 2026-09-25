package com.example.starter.consent.dto;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 撤回响应：返回代次状态；存在生效保留冻结时报告被保留记录数。
 *
 * @param subjectKey          主体标识（合成字符串）
 * @param purpose             用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch               被撤回的授权代次
 * @param status              状态，撤回成功固定为 REVOKED
 * @param retainedRecordCount 因生效保留冻结而不得物理清除的记录数，无生效冻结时为 0
 */
public record RevokeResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        GrantStatus status,
        long retainedRecordCount) {
}
