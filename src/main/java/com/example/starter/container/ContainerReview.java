package com.example.starter.container;

import java.time.LocalDateTime;

/**
 * 容器复核封签记录实体，对应 container_review 表。
 * 同一容器同一保管人至多一条；两名不同保管人复核后容器恢复 SEALED。
 *
 * @param id          主键
 * @param containerId 所属容器业务键
 * @param custodianId 复核保管人
 * @param note        复核说明；NULL 表示未填写
 * @param createdAt   复核时间（Asia/Shanghai）
 */
public record ContainerReview(
        Long id,
        String containerId,
        String custodianId,
        String note,
        LocalDateTime createdAt) {
}
