package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量数据查询创建请求：所有目标主体的当前授权代次均须存在该接收方未到期证明，
 * 任一缺失或已到期则整次 403，不返回任何部分数据。
 *
 * @param recipientId 数据接收方标识
 * @param purpose     查询用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param subjectKeys 目标数据主体标识列表（去重后按字典序稳定处理）
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotEmpty List<@NotBlank @Size(max = 128) String> subjectKeys) {
}
