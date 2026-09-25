package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * epoch 冻结状态视图：代次授权状态、全部冻结（含生效判定）、保留记录计数与解除历史。
 *
 * @param subjectKey          主体标识
 * @param purpose             用途
 * @param epoch               授权代次
 * @param grantStatus         授权状态：ACTIVE 有效 / REVOKED 已撤回
 * @param holds               该代次全部冻结及生效判定
 * @param retainedRecordCount 仍被生效冻结保留的记录数
 * @param releaseHistory      不可变的解除历史（按解除时间升序）
 */
public record EpochHoldStatusResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        GrantStatus grantStatus,
        List<HoldResponse> holds,
        int retainedRecordCount,
        List<HoldReleaseHistoryItem> releaseHistory) {
}
