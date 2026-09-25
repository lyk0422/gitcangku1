package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封存容器实体，对应 sealed_container 表。
 *
 * @param id               主键
 * @param containerKey     容器业务键，全局唯一
 * @param custodianId      容器当前负责人（创建人），巡检与装载操作须由此人执行
 * @param status           容器状态
 * @param nextInspectionAt 下次巡检截止时刻（UTC），每次巡检后必须严格晚于实际巡检时刻
 * @param version          乐观版本号，每次巡检/装载变更递增，参与 inspectKey 指纹
 * @param createdAt        创建时间（Asia/Shanghai）
 * @param updatedAt        最近变更时间（Asia/Shanghai）
 */
public record SealedContainer(
        Long id,
        String containerKey,
        String custodianId,
        ContainerStatus status,
        LocalDateTime nextInspectionAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
