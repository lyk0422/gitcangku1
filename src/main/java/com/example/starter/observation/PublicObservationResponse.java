package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 公开查询响应：按簇的禁运状态返回不同视图。
 * EMBARGOED 时不返回任何簇内容，仅返回禁运元信息与实际剩余秒数。
 *
 * @param observationId   观测记录唯一标识（簇）
 * @param status          状态：PUBLIC（未设禁运）/ EMBARGOED（禁运中）/ RELEASED（已发布）
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601；未设禁运时不返回）
 * @param disclosureScope 披露范围标签（未设禁运时不返回）
 * @param remainingSeconds 距截止的实际剩余秒数（仅 EMBARGOED 时返回）
 * @param version         当前版本号（仅 PUBLIC 时返回）
 * @param deleted         是否删除墓碑（仅 PUBLIC 时返回）
 * @param location        观测地点（仅 PUBLIC 且非墓碑时返回）
 * @param reading         观测读数（仅 PUBLIC 且非墓碑时返回）
 * @param note            观测备注（仅 PUBLIC 且非墓碑时返回）
 * @param release         首次发布快照（仅 RELEASED 时返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicObservationResponse(
        String observationId,
        String status,
        Instant embargoUntilUtc,
        List<String> disclosureScope,
        Long remainingSeconds,
        Integer version,
        Boolean deleted,
        String location,
        String reading,
        String note,
        ReleaseResponse release) {

    /**
     * 未设禁运：直接公开当前内容。
     */
    public static PublicObservationResponse publicView(ObservationSnapshot current) {
        return new PublicObservationResponse(current.observationId(), "PUBLIC", null, null, null,
                current.version(), current.deleted(),
                current.deleted() ? null : current.location(),
                current.deleted() ? null : current.reading(),
                current.deleted() ? null : current.note(), null);
    }

    /**
     * 禁运中：不返回簇内容，仅返回禁运元信息与剩余秒数。
     */
    public static PublicObservationResponse embargoedView(EmbargoRecord embargo, List<String> scope,
                                                          long remainingSeconds) {
        return new PublicObservationResponse(embargo.observationId(), "EMBARGOED",
                embargo.embargoUntilUtc(), scope, remainingSeconds,
                null, null, null, null, null, null);
    }

    /**
     * 已发布：返回首次发布快照。
     */
    public static PublicObservationResponse releasedView(ReleaseResponse release) {
        return new PublicObservationResponse(release.observationId(), "RELEASED",
                release.embargoUntilUtc(), release.disclosureScope(), null,
                null, null, null, null, null, release);
    }
}
