package com.example.starter.api.dto;

/**
 * 作用域锁文件中的一条依赖边视图。
 *
 * @param fromScopeId 声明依赖的制品所在作用域 ID
 * @param fromName    声明依赖的制品名称
 * @param toName      依赖目标名称
 * @param toVersion   目标解析出的精确版本
 * @param scope       依赖作用域：PUBLIC 指向同作用域，PRIVATE 指向子作用域
 * @param toScopeId   目标所在作用域 ID
 */
public record EdgeView(long fromScopeId, String fromName, String toName, int toVersion,
                       String scope, long toScopeId) {
}
