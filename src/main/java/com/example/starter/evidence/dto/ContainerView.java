package com.example.starter.evidence.dto;

import com.example.starter.evidence.ContainerStatus;

import java.time.LocalDateTime;

/**
 * 封存箱视图。
 *
 * @param containerKey 封存箱业务键
 * @param sealNo       箱独立封条编号
 * @param custodianId  当前保管人
 * @param status       箱状态
 * @param version      成员版本（根箱从 1 开始递增）
 * @param parentKey    直接父箱业务键；NULL 表示根箱
 * @param rootKey      所属根箱业务键
 * @param createdAt    创建时间（Asia/Shanghai）
 * @param updatedAt    最近一次变更时间（Asia/Shanghai）
 */
public record ContainerView(
        String containerKey,
        String sealNo,
        String custodianId,
        ContainerStatus status,
        int version,
        String parentKey,
        String rootKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
