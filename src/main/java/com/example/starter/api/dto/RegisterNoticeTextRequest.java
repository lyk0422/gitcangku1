package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 告知文本版本登记请求；登记后初始状态为 DRAFT，须经批准方可用于发布。
 *
 * @param noticeKey 告知文本业务标识
 * @param version   文本版本号，同标识内唯一
 * @param licenseId 文本对应的许可证标识
 * @param body      告知文本正文
 * @param regions   目标地区代码集合，服务端规范化（大写、去重、升序）
 */
public record RegisterNoticeTextRequest(
        @NotBlank String noticeKey,
        @Positive int version,
        @NotBlank String licenseId,
        @NotBlank String body,
        @NotEmpty @Size(max = 32) List<@NotBlank String> regions) {

    public RegisterNoticeTextRequest {
        regions = List.copyOf(regions);
    }
}
