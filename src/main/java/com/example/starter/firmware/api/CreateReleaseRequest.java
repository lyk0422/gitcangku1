package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建发布单请求。levels 缺省为普通灰度发布单；声明 2~5 个递增级别即启用金丝雀分级验证，
 * 初始仅解锁第1级，生效比例取第1级比例（此时 ratio 字段被忽略）。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Valid @Size(min = 2, max = 5) List<CanaryLevelRequest> levels) {

    /**
     * 普通灰度发布单（无金丝雀级别）。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null);
    }
}
