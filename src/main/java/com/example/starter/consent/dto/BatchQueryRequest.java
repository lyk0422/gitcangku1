package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 跨对象查询请求：指定允许区域集合，仅在授权有效、epoch 一致且全部对象驻留区域
 * 均在集合内时返回数据；任一对象区域不允许或代次已撤回时整次 403 或 410，不返回部分结果。
 *
 * @param requestId      幂等请求标识；同参重放返回首次完整快照
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKeys     待查询的记录键集合
 * @param allowedRegions 允许返回的驻留区域集合
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotEmpty @Size(max = 64) List<@NotBlank @Size(max = 128) String> recordKeys,
        @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 32) String> allowedRegions) {
}
