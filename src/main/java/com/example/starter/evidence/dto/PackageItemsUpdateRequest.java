package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量增删证物请求：携带变更后的完整最终证物集合，服务端先整体校验最终集合，
 * 任一件按截至时刻重放失败则整包新版本不生成、已有版本不变。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 变更后的完整最终证物业务键集合（至少一件），服务端去重并规范化排序
 * @param asOfUtc      新版本的截至 UTC 时刻
 */
public record PackageItemsUpdateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotNull LocalDateTime asOfUtc) {
}
