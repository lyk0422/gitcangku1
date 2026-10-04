package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次合格证明细：全部绑定（按绑定提交顺序稳定排序）与放行快照。
 * releaseSnapshot 在未放行或无声明约束的放行时为 null。
 */
public record BatchCertificatesResponse(
        String batchKey,
        List<BindingResponse> bindings,
        ReleaseSnapshotResponse releaseSnapshot
) {
}