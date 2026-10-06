package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备空间档案维护请求：更新总空间与当前镜像字节数（均为正整数且镜像不大于总空间）。
 * expectedDeviceVersion 必须等于当前空间档案版本，成功加一；
 * 调小容量若低于当前镜像加活跃预留则 409，响应携带当前档案版本。
 */
public record MaintainSpaceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Long totalSpaceBytes,
        @NotNull @Min(1) Long currentImageBytes,
        @Min(1) int expectedDeviceVersion) {
}
