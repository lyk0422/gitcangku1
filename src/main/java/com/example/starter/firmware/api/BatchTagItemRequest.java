package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量标签修改中的单项：将指定设备的标签整体替换为给定规范化集合（全量覆盖语义）。
 *
 * @param deviceId 设备ID
 * @param tags     目标标签全集；空列表表示清空该设备标签；null 不合法（必须显式给出）
 */
public record BatchTagItemRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull(message = "tags 不能为 null，空列表表示清空标签")
        List<@NotBlank @Size(max = 64) String> tags) {
}
