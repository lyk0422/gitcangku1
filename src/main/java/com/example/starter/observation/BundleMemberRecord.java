package com.example.starter.observation;

/**
 * 簇成员记录：对应 bundle_member 表的一行，建簇时冻结当前版本。
 *
 * @param id            自增主键
 * @param bundleKey     所属簇标识
 * @param observationId 成员观测标识
 * @param surveyId      成员观测所属调查问卷标识（建簇时快照）
 * @param frozenVersion 建簇时冻结的当前版本号；待恢复墓碑成员为墓碑版本号
 * @param role          成员角色（ACTIVE/PENDING_RESTORE）
 */
public record BundleMemberRecord(
        long id,
        String bundleKey,
        String observationId,
        String surveyId,
        int frozenVersion,
        BundleMemberRole role) {
}
