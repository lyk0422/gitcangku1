package com.example.starter.api.dto;

/**
 * 锁文件中实际保留的依赖边：fromName:fromVersion 声明的、指向 toName 的区间约束。
 */
public record LockEdgeResponse(String fromName, int fromVersion, String toName,
                               int minimumVersion, int maximumVersion) {
}
