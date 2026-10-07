package com.example.starter.api.dto;

/**
 * 锁文件中冻结的依赖边：选中制品声明的、指向另一选中制品的依赖区间。
 */
public record LockEdgeResponse(String fromName, String toName,
                               int minimumVersion, int maximumVersion) {
}
