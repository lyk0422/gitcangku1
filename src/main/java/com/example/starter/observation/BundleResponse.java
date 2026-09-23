package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 关联观测簇视图：建簇响应与证据查询共用，成员按观测标识稳定排序。
 *
 * @param bundleKey        簇唯一业务标识
 * @param surveyId         簇所属调查问卷标识
 * @param status           簇状态（OPEN/CLOSED）
 * @param consistentFields 要求簇内一致的字段名列表（固定顺序 location/reading/note）
 * @param operator         建簇审核员标识
 * @param arbitrationId    结案联合裁决标识；未结时省略
 * @param closedAtUtc      关闭时刻（UTC）；未结时省略
 * @param createdAtUtc     建簇时刻（UTC）
 * @param members          成员视图列表，按 observationId 升序
 * @param openConflicts    未解决冲突视图列表，按 observationId、field 升序
 */
public record BundleResponse(
        String bundleKey,
        String surveyId,
        String status,
        List<String> consistentFields,
        String operator,
        String arbitrationId,
        Instant closedAtUtc,
        Instant createdAtUtc,
        List<MemberView> members,
        List<ConflictView> openConflicts) {

    /**
     * 簇成员视图。
     *
     * @param observationId  成员观测标识
     * @param surveyId       成员观测所属调查问卷标识
     * @param frozenVersion  建簇时冻结的版本号
     * @param role           成员角色（ACTIVE/PENDING_RESTORE）
     * @param currentVersion 查询时该观测的当前版本号
     * @param deleted        查询时该观测是否为墓碑
     */
    public record MemberView(
            String observationId,
            String surveyId,
            int frozenVersion,
            String role,
            int currentVersion,
            boolean deleted) {
    }

    /**
     * 未解决冲突视图（证据查询只读）。
     *
     * @param conflictId       冲突登记自增 id（联合裁决选择据此定位）
     * @param observationId    冲突所在观测标识
     * @param field            冲突字段名
     * @param baseVersion      候选基线版本号
     * @param candidateToken   候选快照指纹，裁决时必须原样回传
     * @param candidateLocation 候选地点完整值
     * @param candidateReading  候选读数完整值
     * @param candidateNote     候选备注完整值
     */
    public record ConflictView(
            long conflictId,
            String observationId,
            String field,
            int baseVersion,
            String candidateToken,
            String candidateLocation,
            String candidateReading,
            String candidateNote) {
    }
}
