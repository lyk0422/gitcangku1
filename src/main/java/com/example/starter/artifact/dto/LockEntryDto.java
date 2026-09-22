package com.example.starter.artifact.dto;

/**
 * 锁文件条目：一个名称锁定的精确版本。
 *
 * @param name    制品名称
 * @param version 锁定的精确版本
 */
public record LockEntryDto(String name, int version) {
}
