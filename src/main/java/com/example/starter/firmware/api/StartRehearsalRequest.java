package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 开始回滚演练请求。
 * 规范设备集合 deviceIds 必须非空、无重复（空集合由业务层返回 422）；集合经去重并按字典序排序后规范化，
 * 参与幂等指纹。
 *
 * @param requestId       幂等请求ID
 * @param operator        操作者，参与幂等指纹
 * @param model           演练目标设备型号
 * @param targetVersion   目标固件版本（正式投放的 to_version）
 * @param rollbackVersion 回退固件版本，必须与目标版本不同
 * @param deviceIds       规范设备集合（非空、无重复）
 */
public record StartRehearsalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String targetVersion,
        @NotBlank @Size(max = 64) String rollbackVersion,
        @NotNull List<@NotBlank @Size(max = 64) String> deviceIds) {
}
