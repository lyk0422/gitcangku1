package com.example.starter.batch.dto;

import java.util.List;

/**
 * 放行持续门禁查询结果：列出当前阻断该批次放行的偏差标识。
 * blockingMajorKeys 为未裁决 MAJOR 偏差；unconfirmedMinorKeys 为未经质控确认的 MINOR 偏差；
 * 两者皆空表示偏差门禁已全部解除（仍需满足检验与双角色批准等既有放行条件）。
 */
public record ReleaseBlockResponse(
        String batchKey,
        boolean releaseBlocked,
        List<String> blockingMajorKeys,
        List<String> unconfirmedMinorKeys
) {
}
