package com.example.starter.domain;

import java.time.Instant;

/**
 * 样本：编号全项目唯一，采集时固化层位、坐标格与 UTC 时刻。
 *
 * @param id 自增主键
 * @param sampleNo 样本编号，全项目唯一
 * @param stratumId 所属层位，采集后不可变更
 * @param gridRef 采集坐标格，采集后不可改写
 * @param collectedAt 采集时刻（UTC），采集时固化
 * @param packageId 当前所在封存包，null 表示未入包
 * @param status 状态：COLLECTED 已采集 / IN_PACKAGE 已入包 / HANDED_OVER 已交接
 * @param createdAt 记录创建时刻（UTC）
 */
public record Sample(long id, String sampleNo, long stratumId, String gridRef, Instant collectedAt,
        Long packageId, String status, Instant createdAt) {
}