package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量数据查询请求：在单一用途下查询多个数据主体的记录。
 *
 * <p>门禁：所有目标主体的“当前授权代次”均须存在该接收方未到期证明；
 * 任一缺失或已到期整次 403，不返回任何部分数据。
 *
 * @param requestId   幂等请求标识
 * @param recipientId 发起查询的接收方标识
 * @param purpose     查询用途（必须与授权及证明的用途一致）
 * @param subjectKeys 目标数据主体标识列表，不允许为空
 * @param recordKey   待查询的记录键
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 128) String> subjectKeys,
        @NotBlank @Size(max = 128) String recordKey) {
}
