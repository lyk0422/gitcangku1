package com.example.starter.api.dto;

/**
 * 锁文件中单条可选依赖的解析结果。
 *
 * @param sourceName      声明该可选依赖的已选制品名称
 * @param dependencyName  可选依赖目标制品名称
 * @param status          INCLUDED 或 SKIPPED
 * @param selectedVersion INCLUDED 时纳入的精确版本号；SKIPPED 时为 null
 * @param reason          SKIPPED 的稳定原因；INCLUDED 时为 null
 */
public record OptionalResultView(
        String sourceName,
        String dependencyName,
        String status,
        Integer selectedVersion,
        String reason) {
}
