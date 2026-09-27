package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工接受被拒观测提交请求：必须填写理由，接受后按提交时保存的候选值执行三方合并。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  人工裁决操作者标识
 * @param reason    人工接受理由（必填）
 */
public record AcceptGeofenceSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 1024) String reason) {
}
