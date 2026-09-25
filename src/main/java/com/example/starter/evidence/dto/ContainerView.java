package com.example.starter.evidence.dto;

import com.example.starter.evidence.ContainerStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封存容器视图：容器当前状态 + 装载证物键集合（排序后返回，集合换序等价）。
 *
 * @param containerKey     容器业务键
 * @param custodianId      容器负责人
 * @param status           容器状态
 * @param nextInspectionAt 下次巡检截止时刻（UTC）
 * @param version          容器版本号
 * @param evidenceKeys     装载证物业务键集合（升序）
 * @param createdAt        创建时间（Asia/Shanghai）
 * @param updatedAt        最近变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerKey,
        String custodianId,
        ContainerStatus status,
        LocalDateTime nextInspectionAt,
        long version,
        List<String> evidenceKeys,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
