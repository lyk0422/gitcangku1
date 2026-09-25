package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测导出视图：冲突簇与导出共用的有效值视图。
 * 未裁决观测应用最新有效附页；已裁决观测冻结在裁决时采用的观测版本，附页不改写裁决结果。
 *
 * @param observationId     观测记录唯一标识
 * @param currentVersion    观测当前版本号
 * @param deleted           是否已删除（墓碑）
 * @param adjudicated       是否已人工裁决
 * @param adjudicatedVersion 裁决时采用（冻结）的观测版本号；未裁决时不返回
 * @param pendingReview     是否存在待复审标记（裁决后又发生附页提交/撤销）
 * @param originalLocation  原始观测地点（当前观测版本值，不含附页）
 * @param originalReading   原始观测读数（当前观测版本值，不含附页）
 * @param originalNote      原始观测备注（当前观测版本值，不含附页）
 * @param effectiveLocation 有效地点：未裁决为应用最新有效附页后的值，已裁决为冻结版本值
 * @param effectiveReading  有效读数，规则同上
 * @param effectiveNote     有效备注，规则同上
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationViewResponse(
        String observationId,
        int currentVersion,
        boolean deleted,
        boolean adjudicated,
        Integer adjudicatedVersion,
        boolean pendingReview,
        String originalLocation,
        String originalReading,
        String originalNote,
        String effectiveLocation,
        String effectiveReading,
        String effectiveNote) {
}
