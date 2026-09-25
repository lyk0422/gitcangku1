package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本及锁定时固化的许可证快照。
 *
 * @param name          被锁定制品名称
 * @param version       被锁定精确版本号
 * @param license       锁定时登记的许可证标识；null 表示当时为 UNKNOWN
 * @param policyVersion 锁定时命名空间策略版本；当时无策略为 0
 */
public record LockEntryResponse(String name, int version, String license, long policyVersion) {
}
