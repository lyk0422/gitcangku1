package com.example.starter.api.dto;

import java.util.List;

/**
 * 作用域锁文件中的单个作用域视图。
 *
 * @param scopeId            作用域 ID（数据库主键）
 * @param parentScopeId      父作用域 ID，根作用域为 null
 * @param parentArtifactName 创建本作用域的父制品名称，根作用域为 null
 * @param depth              嵌套层数：根作用域为 1，每条 PRIVATE 边深入一层，最大 4
 * @param path               从根到本作用域的父制品名称路径（结构化列表，根为空列表）
 * @param entries            本作用域的精确版本条目，按名称升序
 */
public record ScopeView(long scopeId, Long parentScopeId, String parentArtifactName, int depth,
                        List<String> path, List<LockEntryResponse> entries) {
}
