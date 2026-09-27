package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测记录响应：普通版本携带全部业务字段；删除墓碑版本只返回删除状态和版本，不带业务字段。
 * 许可采样观测额外携带设备、网格、采集时刻、命中许可与许可版本、业务状态及复核标记。
 *
 * @param observationId     观测记录唯一标识
 * @param version           版本号
 * @param deleted           是否为删除墓碑
 * @param location          观测地点（墓碑版本不返回）
 * @param reading           观测读数（墓碑版本不返回）
 * @param note              观测备注（墓碑版本不返回）
 * @param deviceId          采样设备标识（旧的直接创建路径不返回）
 * @param gridId            采集网格单元编号（旧路径不返回）
 * @param observedAtUtc     采集时刻（UTC；旧路径不返回）
 * @param permitId          接收时命中的采样许可标识（旧路径不返回）
 * @param permitVersion     接收时命中许可的版本快照（旧路径不返回）
 * @param observationStatus 观测业务状态 RECEIVED/MERGED/PERMIT_REVIEW（旧路径不返回）
 * @param permitReview      是否处于许可撤销人工复核（observationStatus=PERMIT_REVIEW 时为 true）
 * @param reviewReason      复核快照中的撤销原因（仅复核中返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String deviceId,
        Integer gridId,
        Instant observedAtUtc,
        String permitId,
        String permitVersion,
        String observationStatus,
        Boolean permitReview,
        String reviewReason) {

    /**
     * 由精简快照（旧路径与历史版本）构造响应：墓碑版本自动省略业务字段，不携带许可采样信息。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                null, null, null, null, null, null, null, null);
    }

    /**
     * 由完整当前快照构造响应，携带许可采样字段与复核标记。
     */
    public static ObservationResponse ofFull(ObservationSnapshot snapshot, ReviewSnapshot review) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
        boolean underReview = snapshot.observationStatus() == ObservationStatus.PERMIT_REVIEW;
        return new ObservationResponse(
                snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.deviceId(), snapshot.gridId(), snapshot.observedAtUtc(),
                snapshot.permitId(), snapshot.permitVersion(),
                snapshot.observationStatus() == null ? null : snapshot.observationStatus().name(),
                underReview ? Boolean.TRUE : null,
                underReview && review != null ? review.reason() : null);
    }
}
