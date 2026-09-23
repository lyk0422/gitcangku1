package com.example.starter.consent.catalog.dto;

import java.util.List;

/**
 * 迁移预览响应：列出绑定旧用途的全部有效授权、已撤回授权与数据记录，并给出记录映射。
 * 预览只读，不写数据。
 *
 * @param migrationKey   迁移键
 * @param catalogVersion 预览所基于的目录版本（当前 catalogGeneration）
 * @param sourcePurpose  被拆分旧用途代码
 * @param sourceScope    旧用途处理范围（规范化取值列表，稳定排序）
 * @param newPurposes    新用途定义（稳定排序）
 * @param effectiveFrom  生效窗口起点（UTC，左闭）
 * @param effectiveTo    生效窗口终点（UTC，右开）
 * @param activeGrants   全部有效授权（稳定排序），激活时必须逐项回传
 * @param revokedGrants  全部已撤回授权（稳定排序），仅告知
 * @param records        全部数据记录（稳定排序），激活时必须逐项回传
 */
public record MigrationPreviewResponse(String migrationKey, int catalogVersion, String sourcePurpose,
                                       List<String> sourceScope, List<NewPurposeDef> newPurposes,
                                       java.time.Instant effectiveFrom, java.time.Instant effectiveTo,
                                       List<PreviewActiveGrant> activeGrants,
                                       List<PreviewRevokedGrant> revokedGrants,
                                       List<PreviewRecord> records) {
}
