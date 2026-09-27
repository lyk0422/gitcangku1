package com.example.starter.permit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 许可观测响应：含命中许可版本、复核标记（PERMIT_REVIEW）与最近拒绝原因码。
 *
 * @param observationKey   观测业务唯一键
 * @param deviceId         采集设备标识
 * @param gridLon          采集网格点经度
 * @param gridLat          采集网格点纬度
 * @param sampledAt        采集时刻（UTC）
 * @param permitId         接收时唯一命中的许可标识
 * @param permitVersion    命中许可版本号
 * @param content          观测内容原文
 * @param reviewStatus     RECEIVED / MERGED / PERMIT_REVIEW
 * @param permitReview     是否处于许可撤销复核（reviewStatus == PERMIT_REVIEW 的冗余布尔标记，便于查询过滤）
 * @param mergedClusterId  合并所属冲突簇标识；未合并为 null
 * @param rejectReasonCode 最近拒绝原因码；成功接收的观测为 null
 * @param receivedAt       接收完成时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermitObservationResponse(
        String observationKey,
        String deviceId,
        BigDecimal gridLon,
        BigDecimal gridLat,
        Instant sampledAt,
        String permitId,
        int permitVersion,
        String content,
        String reviewStatus,
        boolean permitReview,
        String mergedClusterId,
        String rejectReasonCode,
        Instant receivedAt) {

    public static PermitObservationResponse of(PermitObservation observation) {
        return new PermitObservationResponse(
                observation.observationKey(), observation.deviceId(),
                observation.gridLon(), observation.gridLat(), observation.sampledAt(),
                observation.permitId(), observation.permitVersion(), observation.content(),
                observation.reviewStatus(),
                PermitReviewStatuses.PERMIT_REVIEW.equals(observation.reviewStatus()),
                observation.mergedClusterId(), observation.rejectReasonCode(), observation.receivedAt());
    }
}
