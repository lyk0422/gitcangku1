package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 拆卸部件请求：引用当前安装所在设备的已提交读数，拆卸累计不得小于安装累计。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，计入幂等指纹
 * @param expectedVersion  部件期望版本号，与当前版本不一致时返回 409
 * @param equipmentId      当前安装所在设备标识，与在装记录不一致时返回 409
 * @param readingId        拆卸引用的设备读数标识，引用后该读数禁止修订
 */
public record RemoveComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Long expectedVersion,
        @NotBlank String equipmentId,
        @NotBlank String readingId) {
}