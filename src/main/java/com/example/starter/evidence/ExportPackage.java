package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 导出包实体，对应 export_package 表。仅当包内全部证物摘要重算匹配、
 * 两名不同见证齐备时才生成；生成后不可变，后续内容变化不改写已导出包。
 * 每件证物的裁决版本号见 {@link ExportPackageItem}。
 *
 * @param id          主键
 * @param packageKey  导出包业务标识，生成后不可变；同一 requestId 重放返回该标识
 * @param requestId   导出请求幂等键，全局唯一；同键同证物集合重放首次包，换集合返回 409
 * @param createdAt   导出包生成时间（Asia/Shanghai）
 */
public record ExportPackage(
        Long id,
        String packageKey,
        String requestId,
        LocalDateTime createdAt) {
}
