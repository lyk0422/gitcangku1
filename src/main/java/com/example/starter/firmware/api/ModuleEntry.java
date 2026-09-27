package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备上报模块清单中的一个条目。
 *
 * @param moduleName 模块名
 * @param version    模块版本，公开的点分数字版本；非法格式返回 422
 * @param digest     模块版本摘要，原样保存并固化到任务与判定证据
 */
public record ModuleEntry(
        @NotBlank @Size(max = 64) String moduleName,
        @NotBlank @Size(max = 64) String version,
        @NotBlank @Size(max = 128) String digest) {
}
