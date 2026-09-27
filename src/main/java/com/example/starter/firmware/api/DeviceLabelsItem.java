package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量标签修改请求中的单台设备项：整批替换该设备的规范化标签集合；
 * labels 传空列表表示清空该设备全部标签。重复或空白标签由服务层返回 422（错误原因可区分）。
 */
public record DeviceLabelsItem(
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull @Size(max = 200) List<@Size(max = 64) String> labels) {
}
