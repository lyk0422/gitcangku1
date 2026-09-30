package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 摘要见证请求。两名不同保管人分别对同一封存版本各确认一次。
 *
 * @param commandKey 幂等命令键
 * @param versionNo  被见证的封存版本号；为空时见证当前 ACTIVE 版本
 */
public record DigestWitnessRequest(
        @NotBlank @Size(max = 64) String commandKey,
        Integer versionNo) {
}
