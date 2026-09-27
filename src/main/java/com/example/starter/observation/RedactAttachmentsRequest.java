package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交附件脱敏请求：一次可包含同一观测下的多个附件。
 * 服务端先校验全部附件归属、规则版本与权限，任一失败整次不产生脱敏版本。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param role          请求角色：提交脱敏需要 REDACTOR 或 ADMIN
 * @param operator      执行脱敏的操作者标识
 * @param ruleVersion   已登记的脱敏规则版本（必须为 ACTIVE）
 * @param items         待脱敏附件列表，至少一项；同一附件在一次请求中不得重复
 */
public record RedactAttachmentsRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 128) String ruleVersion,
        @NotEmpty @Valid List<Item> items) {

    /**
     * 单个附件的脱敏请求项。
     *
     * @param attachmentId    待脱敏附件标识（必须属于该观测）
     * @param replacementNote 替换说明：记录替换了什么及如何替换
     */
    public record Item(
            @NotBlank @Size(max = 128) String attachmentId,
            @NotBlank @Size(max = 2048) String replacementNote) {
    }
}
