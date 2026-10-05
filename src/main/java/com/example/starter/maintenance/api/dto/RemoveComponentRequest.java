package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 拆卸部件请求：从当前所在设备拆下，以该设备一条已提交读数标记拆卸终点，
 * 终点与起点累计工时差值计入部件累计寿命。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，参与幂等指纹
 * @param readingId        拆卸引用的设备累计读数标识，须已提交；其累计工时不得小于安装累计
 * @param expectedVersion  部件期望版本号，与当前版本不一致时返回 409
 */
public record RemoveComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String readingId,
        @NotNull Long expectedVersion) {
}
