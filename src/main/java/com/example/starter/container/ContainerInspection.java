package com.example.starter.container;

import java.time.LocalDateTime;

/**
 * 容器巡检记录实体，对应 container_inspection 表。只追加、不可变。
 *
 * @param id                主键
 * @param containerId       所属容器业务键
 * @param inspectKey        巡检幂等键，全局唯一；失败不占键
 * @param inspectorId       检查人
 * @param containerVersion  发起时容器版本，参与巡检指纹
 * @param inspectedAt       实际巡检时刻（UTC），请求指定，可早于计划时刻
 * @param nextInspectionAt  巡检后下次巡检时刻（UTC）
 * @param result            封签结果 PASS/FAIL
 * @param note              巡检说明；FAIL 时非空
 * @param createdAt         记录创建时间（Asia/Shanghai）
 */
public record ContainerInspection(
        Long id,
        String containerId,
        String inspectKey,
        String inspectorId,
        long containerVersion,
        LocalDateTime inspectedAt,
        LocalDateTime nextInspectionAt,
        InspectionResult result,
        String note,
        LocalDateTime createdAt) {
}
