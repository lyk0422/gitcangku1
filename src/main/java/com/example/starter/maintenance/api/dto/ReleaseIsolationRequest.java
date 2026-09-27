package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 解除隔离请求：须确认所有锁定点逐一解除（集合与创建时完全一致，缺失/多余/重复返回 422）
 * 并填写解除原因；解除前若隔离仍生效，新增读数仍返回 409。
 *
 * @param requestId   全局唯一请求标识（幂等键）
 * @param operatorId  提交解除请求的操作者（固化为隔离令解除人）
 * @param reason      解除原因（必填，非空白）
 * @param releases    全部锁定点的逐一解除确认（至少一个）
 */
public record ReleaseIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String operatorId,
        @NotBlank String reason,
        @NotEmpty @Valid List<LockReleaseInput> releases) {
}
