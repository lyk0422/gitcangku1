package com.example.starter.api.dto;

/**
 * 锁文件中的路径排除处置记录。
 *
 * @param outcome APPLIED=实际忽略了同名边；MISS=未命中（允许排除未知名称）
 * @param detail  人读理由：APPLIED 时列出被忽略的边，MISS 时为“未命中”
 */
public record LockExclusionResponse(String fromName, int fromVersion, String toName,
                                    String excludedName, String outcome, String detail) {
}
