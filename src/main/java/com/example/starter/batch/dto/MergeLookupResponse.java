package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 按 requestId 查询合批结果：成功（MERGED）返回首次子批快照；被拒（REJECTED）返回全部 422 拒绝原因。
 * 拒绝不占用 requestId，同一 requestId 可能先被拒多次后成功，成功后不再产生拒绝记录。
 */
public record MergeLookupResponse(
        String requestId,
        String outcome,
        MergeResponse merge,
        List<Rejection> rejections
) {

    /**
     * 单条合批拒绝原因。code 为机器可区分代码，detail 为涉及父批键与实际/期望值的人读说明。
     */
    public record Rejection(
            String reasonCode,
            String reasonDetail,
            Instant rejectedAt
    ) {
    }
}
