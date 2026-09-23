package com.example.starter.blind.dto;

import java.util.List;

/**
 * 知情冲突依据：目标数据采集者中，某人已通过受控揭盲获知某受试者分组，
 * 因而不得再被分配该受试者的数据采集范围。仅给出依据，不回填处理代码盲底。
 *
 * @param actorId           已获知分组的操作者编号
 * @param participantId     其已知情的受试者编号
 * @param unblindRequestId  批准其知情的揭盲申请编号（不可删除的知情历史依据）
 * @param approvedAt        揭盲批准时间，Unix 毫秒 UTC
 */
public record ConflictEvidenceView(
        String actorId,
        String participantId,
        String unblindRequestId,
        long approvedAt
) {
}
