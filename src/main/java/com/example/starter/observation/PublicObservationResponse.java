package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 公开查询响应：禁运期内不返回簇内容；已发布返回首次披露快照；否则返回当前内容。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoed       当前是否处于禁运期内（true 时不返回任何簇内容字段）
 * @param embargoUntilUtc 禁运截止时刻（UTC）；仅禁运期内返回
 * @param released        是否已生成首次披露快照
 * @param version         当前版本号；仅未禁运且未发布时返回
 * @param deleted         当前是否为删除墓碑；仅未禁运且未发布时返回
 * @param location        当前观测地点；仅未禁运且未发布时返回（墓碑为 null）
 * @param reading         当前观测读数；仅未禁运且未发布时返回（墓碑为 null）
 * @param note            当前观测备注；仅未禁运且未发布时返回（墓碑为 null）
 * @param disclosure      首次披露快照及附页；仅已发布时返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicObservationResponse(
        String observationId,
        boolean embargoed,
        Instant embargoUntilUtc,
        Boolean released,
        Integer version,
        Boolean deleted,
        String location,
        String reading,
        String note,
        DisclosureResponse disclosure) {

    /**
     * 禁运期内的响应：只暴露截止时刻，不暴露簇内容。
     */
    public static PublicObservationResponse embargoed(String observationId, Instant embargoUntilUtc) {
        return new PublicObservationResponse(observationId, true, embargoUntilUtc,
                null, null, null, null, null, null, null);
    }

    /**
     * 已发布的响应：返回不可变首次披露快照及附页。
     */
    public static PublicObservationResponse released(DisclosureResponse disclosure) {
        return new PublicObservationResponse(disclosure.observationId(), false, null,
                true, null, null, null, null, null, disclosure);
    }

    /**
     * 无禁运限制的普通公开视图：返回当前内容（墓碑不返回业务字段）。
     */
    public static PublicObservationResponse plain(ObservationSnapshot current) {
        if (current.deleted()) {
            return new PublicObservationResponse(current.observationId(), false, null,
                    false, current.version(), true, null, null, null, null);
        }
        return new PublicObservationResponse(current.observationId(), false, null,
                false, current.version(), false, current.location(), current.reading(),
                current.note(), null);
    }
}