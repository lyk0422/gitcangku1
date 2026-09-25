package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建发布单请求。levels 为空时为普通灰度（ratio 必填）；声明 2~5 个递增级别时启用金丝雀分级，
 * 生效比例取第 1 级比例，此时 ratio 必须缺省或与第 1 级比例一致。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) Integer ratio,
        @Valid @Size(min = 2, max = 5) List<CanaryLevelRequest> levels) {

    /**
     * 普通灰度创建便捷构造：不声明金丝雀级别。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                Integer ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null);
    }
}
