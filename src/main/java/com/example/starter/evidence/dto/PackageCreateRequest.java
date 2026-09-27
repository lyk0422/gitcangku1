package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建案件披露包请求。证物集合按业务键去重后字典序规范化排序；
 * 每件证物必须属于该案件，且按截至 UTC 时刻重放封签、待核验、借出与保管链均通过。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 证物业务键集合（至少一件），服务端去重并规范化排序，重复键视为同一件
 * @param asOfUtc      截至 UTC 时刻，全部历史状态按该时刻判定而非生成时状态
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotNull LocalDateTime asOfUtc) {
}
