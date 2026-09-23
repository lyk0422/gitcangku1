package com.example.starter.blind.dto;

import java.util.List;

/**
 * 职责轮换预览视图（只读、不写数据）：
 * 基于当前不可删除的知情历史，计算目标名册对全部未结束受试者的可见范围与冲突。
 *
 * @param experimentId          实验编号
 * @param currentVersion        当前职责名册版本
 * @param targetRoster          目标名册（已排序规范化）
 * @param collectorScopes       每个目标采集者的可见范围与知情冲突
 * @param conflicts             全部知情冲突依据（扁平，便于审阅）
 * @param roleOverlapConflicts  同一人同时承担采集与随机化保管的冲突人员
 * @param valid                 目标名册是否可激活（无角色交集且无未结束受试者知情冲突）
 */
public record RotationPreviewView(
        String experimentId,
        int currentVersion,
        RosterView targetRoster,
        List<CollectorScopePreviewView> collectorScopes,
        List<ConflictEvidenceView> conflicts,
        List<String> roleOverlapConflicts,
        boolean valid
) {
}
