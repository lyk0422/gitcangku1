package com.example.starter.firmware.domain;

/**
 * 兼容矩阵登记历史（只增不改）。
 *
 * @param version          矩阵版本，从1开始
 * @param requirementCount 本次登记固件需求条数
 * @param dependencyCount  本次登记模块依赖条数
 * @param registeredAtUtc  登记时刻，UTC，ISO-8601
 */
public record MatrixRevision(int version, int requirementCount, int dependencyCount,
                             String registeredAtUtc) {
}
