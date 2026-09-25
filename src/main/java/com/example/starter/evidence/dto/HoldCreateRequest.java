package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 建立保全冻结请求。证物集合在服务端去重并按字典序规范化；
 * 区间为 UTC 左闭右开 [effectiveAt, expireAt)，生效时刻不得早于提交时刻（禁止补建覆盖过去的冻结）。
 *
 * @param commandKey  幂等命令键
 * @param holdId      冻结业务键，全局唯一
 * @param caseKey     冻结关联案件号
 * @param reason      冻结原因，非空
 * @param effectiveAt UTC 生效时刻（含）
 * @param expireAt    UTC 失效时刻（不含）
 * @param evidenceKeys 被冻结证物业务键集合（非空，服务端规范化排序去重）
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String holdId,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 512) String reason,
        @NotNull LocalDateTime effectiveAt,
        @NotNull LocalDateTime expireAt,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
