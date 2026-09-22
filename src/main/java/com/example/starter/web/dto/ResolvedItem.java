package com.example.starter.web.dto;

/**
 * 锁文件明细项：每个名称解析出的唯一精确版本。
 */
public record ResolvedItem(
        String name,
        Integer version
) {
}
