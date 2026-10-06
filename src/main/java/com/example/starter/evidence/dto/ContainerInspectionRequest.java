package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 箱封条核验请求。仅当前保管人可提交；失败使该箱进入 SEAL_BROKEN 并使根箱不能交接。
 *
 * @param requestId 全局幂等请求键
 * @param passed    核验结果：true 通过 / false 失败
 * @param note      核验备注，可空
 */
public record ContainerInspectionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Boolean passed,
        @Size(max = 512) String note) {
}
