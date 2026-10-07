package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记首个介质请求。仅当前保管人可登记，证物已有介质时须走迁移；
 * 登记成功即为 ACTIVE，保存合成内容的 SHA-256。
 *
 * @param commandKey 幂等命令键
 * @param mediaNo    介质编号，证物内唯一
 * @param sha256     合成内容的 SHA-256（小写十六进制，64 字符）
 */
public record MediaRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String mediaNo,
        @NotBlank @Size(min = 64, max = 64) String sha256) {
}
