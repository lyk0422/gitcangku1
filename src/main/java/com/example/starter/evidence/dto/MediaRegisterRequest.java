package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 介质登记请求。仅当前保管人可登记；证物尚不存在 ACTIVE 介质时登记即为当前读取来源。
 *
 * @param commandKey 幂等命令键
 * @param mediaNo    介质编号，同一证物内唯一
 * @param sha256     介质合成内容的 SHA-256 摘要（64 位小写十六进制）
 */
public record MediaRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String mediaNo,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "sha256 须为 64 位小写十六进制")
        String sha256) {
}
