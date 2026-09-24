package com.example.starter.race.persistence;

import com.example.starter.race.domain.AdvancementListStatus;

import java.util.List;

/**
 * advancement_list 头表与条目、未晋级清单合成的晋级名单快照。
 *
 * @param advancementKey 全局唯一名单键，撤销后也不可复用
 * @param raceId         所属赛事ID
 * @param version        名单生成后的赛事版本号
 * @param status         ACTIVE / REVOKED
 * @param directQuota    每组直接晋级名额 Q
 * @param wildcardQuota  跨组补位名额 W
 * @param requestId      生成请求的幂等键
 * @param generatedAt    名单生成时刻，Unix毫秒时间戳
 * @param revokedAt      撤销时刻，Unix毫秒时间戳；生效中为 null
 * @param entries        不可变晋级条目（展示顺序排列）
 * @param nonAdvanced    同步固化的未晋级有效选手（展示顺序排列）
 */
public record AdvancementListRow(
        String advancementKey,
        String raceId,
        int version,
        AdvancementListStatus status,
        int directQuota,
        int wildcardQuota,
        String requestId,
        long generatedAt,
        Long revokedAt,
        List<AdvancementEntryRow> entries,
        List<AdvancementNonAdvancedRow> nonAdvanced
) {

    /** 仅头表字段的构造器（查询列表概览时使用）。 */
    public AdvancementListRow(
            String advancementKey,
            String raceId,
            int version,
            AdvancementListStatus status,
            int directQuota,
            int wildcardQuota,
            String requestId,
            long generatedAt,
            Long revokedAt) {
        this(advancementKey, raceId, version, status, directQuota, wildcardQuota,
                requestId, generatedAt, revokedAt, List.of(), List.of());
    }
}
