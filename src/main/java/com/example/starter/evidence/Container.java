package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器实体，对应 container 表。
 * version 随箱内证物放入/移出自增；状态变更必须先锁定容器行。
 *
 * @param id           主键
 * @param containerKey 容器业务键，全局唯一
 * @param location     当前存放位置描述
 * @param version      容器版本：箱内证物每次放入/移出自增
 * @param status       容器状态
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次版本或状态变更时间（Asia/Shanghai）
 */
public record Container(
        Long id,
        String containerKey,
        String location,
        long version,
        ContainerStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
