package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 接触批次登记请求。批次业务键取 requestId；成员须为同一保管人持有的 2 至 5 件 SEALED 证物。
 *
 * @param requestId    全局请求标识（幂等键，同时作为批次业务键）
 * @param evidenceKeys 成员证物业务键，2 至 5 件，不得重复
 */
public record ContactBatchCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
