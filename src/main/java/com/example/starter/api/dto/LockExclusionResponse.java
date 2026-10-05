package com.example.starter.api.dto;

/**
 * 锁文件中一条生效边上声明的路径排除记录。
 *
 * @param hit true=子图中存在同名边被该排除命中；false=未命中（含未知名称）
 */
public record LockExclusionResponse(String fromName, int fromVersion, String toName,
                                    String excludedName, boolean hit) {
}
