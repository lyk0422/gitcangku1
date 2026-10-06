package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次作用域锁定的完整解：结构化的作用域树与依赖边。
 *
 * <p>作用域以索引互相引用（父作用域索引先于子作用域），路径为制品名称列表，
 * 不使用未转义分隔符拼接。
 *
 * @param scopes 全部作用域（索引即创建顺序，根作用域索引为 0）
 * @param edges  全部依赖边（按 fromScopeIndex、fromName、toName、scope 排序）
 */
public record ScopedLockSolution(List<ResolvedScope> scopes, List<ResolvedEdge> edges) {

    /**
     * 一个已解析作用域。
     *
     * @param index              作用域索引（父索引恒小于子索引）
     * @param parentIndex        父作用域索引，根作用域为 -1
     * @param parentArtifactName 创建本作用域的父制品名称，根作用域为 null
     * @param depth              嵌套层数：根作用域为 1，每条 PRIVATE 边深入一层
     * @param path               从根到本作用域的父制品名称路径（结构化列表，根为空列表）
     * @param entries            本作用域内名称 -> 精确版本（名称升序）
     */
    public record ResolvedScope(int index, int parentIndex, String parentArtifactName, int depth,
                                List<String> path, Map<String, Integer> entries) {
    }

    /**
     * 一条已解析依赖边。
     *
     * @param fromScopeIndex 声明依赖的制品所在作用域索引
     * @param fromName       声明依赖的制品名称
     * @param toName         依赖目标名称
     * @param toVersion      目标解析出的精确版本
     * @param scope          依赖作用域（PUBLIC 指向同作用域，PRIVATE 指向子作用域）
     * @param toScopeIndex   目标所在作用域索引
     */
    public record ResolvedEdge(int fromScopeIndex, String fromName, String toName, int toVersion,
                               DependencyScope scope, int toScopeIndex) {
    }
}
