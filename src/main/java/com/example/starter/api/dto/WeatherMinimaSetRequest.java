package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 整体替换某区域气象最低标准请求。提交前必须校验完整最终状态：
 * 任一时段退化（起点 >= 终点）、标准为负值或时段重叠都返回 422，不产生任何写入。
 *
 * @param zoneId    区域唯一标识（须已存在）
 * @param windows   新的标准时段集合（整体替换；空列表表示清除该区域标准）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record WeatherMinimaSetRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Valid @Size(max = 100) List<WeatherMinimaWindowDto> windows,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
