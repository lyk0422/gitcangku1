package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 阅检副本签发请求。仅当前保管人可签发；签发后摘要、水印与有效区间冻结。
 * 有效区间为 UTC 左闭右开 [签发时刻, validUntil)，最长一小时。
 *
 * @param commandKey 幂等命令键
 * @param copyKey    副本业务键（不可重复副本 ID），全局唯一，进入水印
 * @param reviewerId 阅检人，仅本人可在有效期内读取副本
 * @param validUntil UTC 失效时刻（不含），须晚于服务端当前时刻且不超过签发后一小时
 */
public record InspectionCopyIssueRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String copyKey,
        @NotBlank @Size(max = 64) String reviewerId,
        @NotNull LocalDateTime validUntil) {
}