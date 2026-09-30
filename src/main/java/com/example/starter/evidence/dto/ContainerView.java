package com.example.starter.evidence.dto;

import com.example.starter.evidence.ContainerStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器视图。
 *
 * @param containerKey 容器业务键
 * @param location     当前存放位置描述
 * @param version      容器版本（箱内证物每次放入/移出自增）
 * @param status       容器状态
 * @param currentItems 当前箱内证物业务键清单（按入库顺序）
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次版本或状态变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerKey,
        String location,
        long version,
        ContainerStatus status,
        List<String> currentItems,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
