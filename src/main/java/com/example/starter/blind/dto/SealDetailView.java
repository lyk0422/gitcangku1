package com.example.starter.blind.dto;

import java.util.List;

/**
 * 封存明细视图：封存元信息 + 该版本上的分配明细；读取不改变状态，不含具体序列。
 *
 * @param seal        封存元信息（摘要、容量、代码集合、已分/余量实际值）
 * @param allocations 该版本上的分配明细，按分配时刻升序
 */
public record SealDetailView(
        SealView seal,
        List<SealAllocationItem> allocations
) {
}
