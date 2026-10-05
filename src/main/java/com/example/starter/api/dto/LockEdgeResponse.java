package com.example.starter.api.dto;

/**
 * 锁文件中一条实际保留（生效）的依赖边。
 */
public record LockEdgeResponse(String fromName, int fromVersion, String toName, int toVersion) {
}
