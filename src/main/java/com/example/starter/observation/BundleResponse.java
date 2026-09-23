package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 关联簇响应：簇信息、冻结成员（含冻结版本与墓碑标记）及登记的全部字段冲突，按观测、字段稳定排序。
 *
 * @param bundleKey        关联簇唯一标识
 * @param surveyId         簇内观测所属调查唯一标识
 * @param consistentFields 声明必须一致的字段名列表
 * @param status           簇状态：OPEN / CLOSED
 * @param operator         建簇审核员标识
 * @param closedAtUtc      簇关闭时刻（UTC）；OPEN 时为 null
 * @param members          冻结成员列表，按观测标识稳定排序
 * @param conflicts        簇内全部字段冲突，按观测标识、字段名稳定排序
 */
public record BundleResponse(
        String bundleKey,
        String surveyId,
        List<String> consistentFields,
        String status,
        String operator,
        Instant closedAtUtc,
        List<MemberView> members,
        List<FieldConflictResponse> conflicts) {

    /**
     * 冻结成员视图。
     *
     * @param observationId       成员观测记录唯一标识
     * @param frozenVersion       建簇时冻结的 currentVersion
     * @param tombstoneAtFreeze   建簇时是否为墓碑（true 表示只能作为待恢复项）
     * @param baseVersion         存活成员离线修改基线版本号；墓碑成员为 null
     * @param remoteLocation      存活成员离线候选地点；墓碑成员为 null
     * @param remoteReading       存活成员离线候选读数；墓碑成员为 null
     * @param remoteNote          存活成员离线候选备注；墓碑成员为 null
     */
    public record MemberView(
            String observationId,
            int frozenVersion,
            boolean tombstoneAtFreeze,
            Integer baseVersion,
            String remoteLocation,
            String remoteReading,
            String remoteNote) {

        public static MemberView of(BundleMemberRecord record) {
            return new MemberView(record.observationId(), record.frozenVersion(), record.tombstoneAtFreeze(),
                    record.baseVersion(), record.remoteLocation(), record.remoteReading(), record.remoteNote());
        }
    }
}
