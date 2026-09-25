package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测导出视图：未裁决观测应用最新有效附页后的有效值；已人工裁决的观测冻结裁决结果，
 * 附页不改写有效值（仅产生待复审标记）。删除墓碑只返回删除状态和版本。
 *
 * @param observationId             观测记录唯一标识
 * @param version                   当前观测版本号
 * @param deleted                   是否为删除墓碑
 * @param resolved                  是否已人工裁决（已裁决的簇冻结裁决时采用的观测版本）
 * @param location                  有效观测地点（墓碑不返回）
 * @param reading                   有效观测读数（墓碑不返回）
 * @param note                      有效观测备注（墓碑不返回）
 * @param appliedCorrigendumVersion 生效的附页版本号；未应用附页（无有效附页或已裁决冻结）时为 null
 * @param pendingReReviewCount      待复审标记数量
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportViewResponse(
        String observationId,
        int version,
        boolean deleted,
        boolean resolved,
        String location,
        String reading,
        String note,
        Integer appliedCorrigendumVersion,
        int pendingReReviewCount) {
}
