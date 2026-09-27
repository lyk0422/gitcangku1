package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量标签修改请求：对多台设备整体替换标签集合。整批先预检（设备存在、设备不重复、标签合法），
 * 再在单事务内提交；任一失败全部回滚、不留半成品、不占幂等键。
 */
public record BatchLabelUpdateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @NotEmpty @Size(max = 500) @Valid List<DeviceLabelsItem> items) {
}
