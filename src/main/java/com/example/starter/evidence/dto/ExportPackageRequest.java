package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 导出包请求。requestId 相同重放首次包标识与摘要清单；同 requestId 换证物集合返回 409。
 *
 * @param requestId    导出请求幂等键
 * @param evidenceKeys 包内证物业务键集合，须非空；服务端排序后逐个裁决，顺序不影响结果
 */
public record ExportPackageRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
