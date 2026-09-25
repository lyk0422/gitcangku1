package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 锁定图来源策略的某个不可变版本。
 *
 * <p>策略版本只能整体新增，不可原地改写；坐标集合按名称规范排序。
 *
 * @param lockfileName 策略适用的锁定图名称
 * @param version      策略版本号，从 1 开始只增
 * @param createdAt    策略版本创建时间，UTC
 * @param coordinates  坐标要求集合，按名称升序
 */
public record ProvenancePolicy(
        String lockfileName,
        int version,
        Instant createdAt,
        List<PolicyCoordinate> coordinates) {
}
