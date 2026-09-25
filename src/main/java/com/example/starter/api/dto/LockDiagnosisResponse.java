package com.example.starter.api.dto;

import java.util.List;

/**
 * 违规诊断查询结果：按当前仓库状态的解析闭包、许可证与策略违规列表。
 *
 * @param feasible       依赖解析是否可行；不可行时 entries 与 violations 为空
 * @param policyVersion  诊断时生效的策略版本号；null 表示该命名空间未配置策略
 * @param entries        解析闭包中每个名称的精确版本与当前许可证（名称升序）
 * @param violations     策略违规列表，按（名称, 版本）稳定升序；为空表示可通过校验
 */
public record LockDiagnosisResponse(
        String rootName,
        int rootVersion,
        long repositoryVersion,
        boolean feasible,
        Long policyVersion,
        List<LockLicenseEntryResponse> entries,
        List<LicenseViolation> violations) {
}
