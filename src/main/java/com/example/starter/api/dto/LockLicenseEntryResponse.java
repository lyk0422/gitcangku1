package com.example.starter.api.dto;

/**
 * 锁文件许可证快照中的单条条目：锁定时固化的精确版本与许可证。
 */
public record LockLicenseEntryResponse(
        String name,
        int version,
        String license) {
}
