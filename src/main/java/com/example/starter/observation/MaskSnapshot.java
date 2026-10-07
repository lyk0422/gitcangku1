package com.example.starter.observation;

import java.util.List;

/**
 * 栅格观测版本快照：元数据（版本号、基版、操作者、墓碑标记）与完整 16 格图。
 *
 * @param observationId 栅格观测唯一标识
 * @param version       版本号，从 1 开始
 * @param baseVersion   该版本合并所基于的基版号；创建版本与删除墓碑为 null
 * @param operator      写入该版本的操作者标识（客户端来源），非空
 * @param deleted       是否为删除墓碑：true 时 cells 为 null
 * @param cells         16 个格值，按行优先排列；删除墓碑版本为 null
 */
public record MaskSnapshot(
        String observationId,
        int version,
        Integer baseVersion,
        String operator,
        boolean deleted,
        List<Integer> cells) {
}
