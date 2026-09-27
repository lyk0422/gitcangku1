package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 附件脱敏请求：对同一观测记录的一个或多个附件生成递增的脱敏版本。
 * 先校验全部附件的归属、规则版本与角色权限，任一失败整次不产生脱敏版本。
 * redactedSummary 与 replacementNote 长度上限由服务端校验，超长返回 422 并携带实际值与要求值。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param ruleVersion     已登记的脱敏规则版本
 * @param replacementNote 替换说明
 * @param role            操作角色，仅 REDACTOR 允许脱敏
 * @param items           脱敏附件项，附件必须都属于该观测记录且不得重复
 */
public record RedactRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String ruleVersion,
        @NotBlank String replacementNote,
        @NotBlank @Size(max = 32) String role,
        @NotEmpty List<@Valid RedactItem> items) {

    /**
     * 单个附件的脱敏项。
     *
     * @param attachmentId    附件唯一标识
     * @param redactedSummary 受限展示摘要
     */
    public record RedactItem(
            @NotBlank @Size(max = 64) String attachmentId,
            @NotBlank String redactedSummary) {
    }
}
