package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 附件脱敏批次请求：一次请求可含多个附件，全部归属、规则版本与权限校验通过后才在单事务内生成版本；
 * 任一附件校验失败，整次请求不产生任何脱敏版本。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param batchId   脱敏批次标识，同一次请求内的多附件共用
 * @param operator  发起脱敏的操作者标识
 * @param reason    本次脱敏理由（批次内共用）
 * @param items     各附件的脱敏条目
 */
public record CreateRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String batchId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 1024) String reason,
        @NotEmpty @Valid List<RedactionItem> items) {

    /**
     * 单个附件的脱敏条目：指定附件、规则版本与替换说明，脱敏摘要由调用方按规则给出。
     *
     * @param attachmentId    被脱敏的附件标识
     * @param ruleCode        脱敏规则标识
     * @param ruleVersion     脱敏规则版本号
     * @param redactedSummary 受限展示用脱敏摘要
     * @param replacementNote 替换说明
     */
    public record RedactionItem(
            @NotBlank @Size(max = 64) String attachmentId,
            @NotBlank @Size(max = 64) String ruleCode,
            @NotNull Integer ruleVersion,
            @NotBlank @Size(max = 2048) String redactedSummary,
            @NotBlank @Size(max = 1024) String replacementNote) {
    }
}
