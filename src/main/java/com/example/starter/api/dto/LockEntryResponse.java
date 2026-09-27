package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本，固化锁定时的摘要与见证版本。
 *
 * @param checksum           锁定时该版本已登记的内容校验摘要（规范化小写）
 * @param attestationVersion 锁定时该版本的见证版本号；后续见证撤销不改写该值
 */
public record LockEntryResponse(String name, int version, String checksum, int attestationVersion) {
}
