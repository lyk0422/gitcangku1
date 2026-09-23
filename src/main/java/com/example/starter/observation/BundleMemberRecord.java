package com.example.starter.observation;

/**
 * 关联簇成员冻结记录：对应 observation_bundle_member 表的一行，建簇时冻结，永不更新。
 *
 * @param bundleKey         所属关联簇唯一标识
 * @param observationId     成员观测记录唯一标识
 * @param surveyId          成员所属调查（survey）唯一标识
 * @param frozenVersion     建簇时冻结的 currentVersion；墓碑成员为墓碑版本号
 * @param tombstoneAtFreeze 建簇时该观测是否为墓碑：true 表示只能作为待恢复项
 * @param baseVersion       存活成员离线修改所基于的基线版本号；墓碑成员为 null
 * @param remoteLocation    存活成员离线候选地点完整值；墓碑成员为 null
 * @param remoteReading     存活成员离线候选读数完整值（十进制原文）；墓碑成员为 null
 * @param remoteNote        存活成员离线候选备注完整值；墓碑成员为 null
 */
public record BundleMemberRecord(
        String bundleKey,
        String observationId,
        String surveyId,
        int frozenVersion,
        boolean tombstoneAtFreeze,
        Integer baseVersion,
        String remoteLocation,
        String remoteReading,
        String remoteNote) {
}
