package com.example.starter.water.dto;

/**
 * 仅携带幂等命令键的命令（批准/取消申请、取消限供）。
 *
 * @param commandKey 幂等命令键
 */
public record KeyedCommand(String commandKey) {
}
