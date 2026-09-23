package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 召回闭包只读查询条目：闭包成员（含被召回祖先自身）的当前版本、状态，
 * 以及该批次到召回祖先的完整祖先链业务键（祖先在前、不含批次自身）；祖先自身的 path 为空列表。
 */
public record ClosureEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        long version,
        List<String> path
) {
}
