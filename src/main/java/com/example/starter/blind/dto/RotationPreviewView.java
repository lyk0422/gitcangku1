package com.example.starter.blind.dto;

import java.util.List;

/**
 * 轮换预览结果：基于当前不可删除的知情历史计算，不写任何数据。
 *
 * @param experimentId              实验编号
 * @param currentVersion            当前实验版本号
 * @param expectedExperimentVersion 请求携带的期望版本号
 * @param versionMatch              期望版本与当前版本是否一致
 * @param effectiveAt               请求的新代次生效时刻，Unix 毫秒，UTC
 * @param rosterViolations          名册结构违规（空名册、采集与保管同人、编号非法等）
 * @param conflicts                 知情冲突：目标采集人已知悉某未结束受试者分组
 * @param visibility                目标名册下每人每职责对未结束受试者的可见范围
 * @param activatable               当前时刻激活是否可通过全部校验
 */
public record RotationPreviewView(
        String experimentId,
        int currentVersion,
        Long expectedExperimentVersion,
        boolean versionMatch,
        Long effectiveAt,
        List<String> rosterViolations,
        List<KnowledgeConflict> conflicts,
        List<VisibilityEntry> visibility,
        boolean activatable
) {
    /** 知情冲突条目：人员、受试者与知情依据。 */
    public record KnowledgeConflict(String actorId, String participantId, String basis) {
    }

    /** 可见范围条目：人员在目标职责下可见的未结束受试者与最小字段。 */
    public record VisibilityEntry(String actorId, String roleType,
                                  List<String> participantIds, List<String> visibleFields) {
    }
}
