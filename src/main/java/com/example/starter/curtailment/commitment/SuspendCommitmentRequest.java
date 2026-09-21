package com.example.starter.curtailment.commitment;

/**
 * 暂停承诺请求。
 *
 * @param commandKey 命令幂等键
 */
public record SuspendCommitmentRequest(String commandKey) {
}
