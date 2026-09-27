package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 附件脱敏提交请求：一次可含多个附件；服务端先校验全部归属、规则版本与权限，任一失败整次不产生脱敏版本。
 *
 * @param requestId 全局唯一请求标识（幂等去重键，复用 request_log）
 * @param ruleVersion 本次脱敏使用的规则版本（提交时必须为已登记且 ACTIVE）
 * @param items   各附件的脱敏条目，附件不可重复
 */
public record CreateRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String ruleVersion,
        @NotEmpty @Valid List<Item> items) {

    /**
     * 单个附件的脱敏条目。
     *
     * @param attachmentId    附件唯一标识（必须属于路径中的观测记录）
     * @param replacementNote 替换说明（脱敏后公开展示的受限摘要原文）
     */
    public record Item(
            @NotBlank @Size(max = 128) String attachmentId,
            @NotBlank @Size(max = 1024) String replacementNote) {
    }
}
