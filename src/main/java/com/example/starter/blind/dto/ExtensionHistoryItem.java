package com.example.starter.blind.dto;

/**
 * 扩容历史单条视图；只暴露元数据，不含处理映射。
 *
 * @param extensionKey    扩容幂等键
 * @param expectedVersion 提交时的期望（扩容前）版本号
 * @param version         该次扩容后的实验版本号
 * @param fromBlockCount  扩容前区组总数
 * @param addedBlockCount 本次追加区组数量
 * @param fromBlockNo     本次首个新区组号
 * @param toBlockNo       本次最后一个新区组号
 * @param operatorActor   执行扩容的协调员操作者编号
 * @param createdAt       扩容提交时间，Unix 毫秒，UTC
 */
public record ExtensionHistoryItem(
        String extensionKey,
        int expectedVersion,
        int version,
        int fromBlockCount,
        int addedBlockCount,
        int fromBlockNo,
        int toBlockNo,
        String operatorActor,
        long createdAt
) {
}
