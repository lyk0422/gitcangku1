package com.example.starter.evidence.destruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 销毁执行命令。销毁令 APPROVED 后由原提交保管人一次提交；
 * 事务内重查全部证物状态与冻结关系，全部通过才置为 DESTROYED 终态并封存保管链。
 *
 * @param commandKey 幂等命令键
 */
public record DestructionExecuteRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
