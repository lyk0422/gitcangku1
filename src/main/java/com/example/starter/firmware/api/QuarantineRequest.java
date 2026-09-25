package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备隔离请求。expectedVersion 为提交时设备当前版本，须与库内一致（并发版本校验）。
 *
 * @param requestId       全局唯一请求ID（isolationKey）
 * @param operator        提交隔离的运维工号
 * @param reasonCode      异常原因代码
 * @param expectedVersion 提交时设备当前固件版本
 */
public record QuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String reasonCode,
        @NotBlank @Size(max = 64) String expectedVersion) {
}
