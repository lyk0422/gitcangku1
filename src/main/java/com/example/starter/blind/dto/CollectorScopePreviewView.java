package com.example.starter.blind.dto;

import java.util.List;

/**
 * 某目标采集者对未结束受试者的可见范围预览。
 *
 * @param actorId            采集者编号
 * @param visibleParticipants 排除知情冲突后可被分配采集的受试者编号（升序）
 * @param blocked            因已揭盲知情而不得分配的受试者依据
 */
public record CollectorScopePreviewView(
        String actorId,
        List<String> visibleParticipants,
        List<ConflictEvidenceView> blocked
) {
}
