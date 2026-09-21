package com.example.starter.curtailment.dispatch;

/**
 * 仅携带幂等键的命令请求（发布、取消）。
 *
 * @param commandKey 命令幂等键
 */
public record CommandRequest(String commandKey) {
}
