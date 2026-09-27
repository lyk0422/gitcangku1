package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 附件脱敏请求：一次请求可含同一观测记录下的多个附件项；先校验全部归属、规则与权限，
 * 任一失败整次不产生脱敏版本。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param redactionId 全局唯一脱敏请求标识，一次请求的多个附件项共享同一标识
 * @param items       脱敏附件项列表，至少一项
 */
public record RedactAttachmentsRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String redactionId,
        @NotEmpty @Valid List<RedactItem> items) {

    /**
     * 单个脱敏附件项。
     *
     * @param attachmentId    附件唯一标识，必须属于路径中的观测记录
     * @param ruleVersion     脱敏规则版本，必须已登记且未撤销
     * @param replacementNote 替换说明，随脱敏版本永久保留
     */
    public record RedactItem(
            @NotBlank @Size(max = 128) String attachmentId,
            @NotBlank @Size(max = 64) String ruleVersion,
            @NotBlank @Size(max = 1024) String replacementNote) {
    }
}
