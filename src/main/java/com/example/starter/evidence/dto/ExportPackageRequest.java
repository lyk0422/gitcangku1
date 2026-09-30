package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 导出包生成请求。同一 requestId 重放：相同证物集合返回首次包标识与摘要清单；
 * 更换证物集合返回 409。
 *
 * @param requestId    导出请求幂等键
 * @param evidenceKeys 本次导出包包含的证物业务键集合（至少一件，服务层去重排序后裁决）
 */
public record ExportPackageRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
