package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销许可请求：许可不再命中任何提交；未合并观测标记 PERMIT_REVIEW 并写入复核快照，
 * 已人工裁决的冲突不重算。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 */
public record RevokePermitRequest(
        @NotBlank @Size(max = 128) String requestId) {
}
