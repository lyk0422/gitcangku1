package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 受限批量查询请求：按对象数核销用途余额，全部对象与剩余额度校验通过后才返回数据并记账。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回首次成功快照
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    查询用途：RESEARCH 研究 / PERSONALIZATION 个性化，不同用途独立计数
 * @param epoch      调用方认定的授权代次；必须与当前有效代次一致，否则按对象跨 epoch 拒绝
 * @param quantity   要求的对象数量；必须等于 recordKeys 去重后的集合大小
 * @param recordKeys 待查询对象键集合（去重后），不允许为空或包含重复元素
 */
public record PurposeQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotNull Integer quantity,
        @NotEmpty List<@NotBlank @Size(max = 128) String> recordKeys) {
}
