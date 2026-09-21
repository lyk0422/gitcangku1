package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 封条核验请求。仅当前保管人可提交；失败使证物进入 SEAL_BROKEN。
 *
 * @param commandKey 幂等命令键
 * @param passed     核验结果：true 通过 / false 失败
 * @param note       核验备注，可空
 */
public record SealInspectionRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull Boolean passed,
        @Size(max = 512) String note) {
}
