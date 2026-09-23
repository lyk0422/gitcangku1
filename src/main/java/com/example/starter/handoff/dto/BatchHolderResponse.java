package com.example.starter.handoff.dto;

/**
 * 批次当前持有厂只读视图：holderPlant 为当前持有厂标识，version 为当前版本，
 * status 为批次当前状态；接收成功后 holderPlant 在同一事务内一次性切换为目标厂，version 加一。
 */
public record BatchHolderResponse(
        String batchKey,
        String holderPlant,
        long version,
        String status
) {
}
