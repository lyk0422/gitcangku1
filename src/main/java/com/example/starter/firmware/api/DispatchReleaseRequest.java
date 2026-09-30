package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布人按设备当次冻结画像批量筛选投放请求。同 requestId 重放首次目标清单；
 * expectedVersion 与发布单当前版本不一致、或换候选设备集合重放，均返回 409。
 *
 * @param requestId       幂等请求ID
 * @param expectedVersion 期望的发布单当前版本，用于乐观并发控制
 * @param deviceIds       候选设备ID集合，不能为空
 */
public record DispatchReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @NotEmpty List<@NotBlank @Size(max = 64) String> deviceIds) {
}
