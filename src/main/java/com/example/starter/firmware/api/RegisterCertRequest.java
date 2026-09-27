package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记/轮换证书链请求。previousChainVersion 为旧证书链版本：目标固件版本已有链时必填（轮换），
 * 首条链登记时必须为空。deviceIds 为登记同时授予信任的设备集合（可空）。
 */
public record RegisterCertRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotBlank @Size(max = 64) String chainVersion,
        @NotBlank @Size(max = 40) String validFromUtc,
        @NotBlank @Size(max = 40) String validToUtc,
        @Size(max = 64) String previousChainVersion,
        List<@NotBlank @Size(max = 64) String> deviceIds,
        @NotBlank @Size(max = 64) String operator) {
}
