package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 召回处置二审确认请求体（生产负责人，必须不同于提交人，通过 X-Actor-Id 传入）。
 * expectedDispositionVersion 必须等于处置单冻结版本；
 * batchVersions 必须携带闭包内全部批次当前版本（batchKey → version），
 * 缺漏、多余或版本不一致均 409；期间闭包、状态或路径变化同样 409。
 */
public record DispositionConfirmRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "expectedDispositionVersion 不能为空") Integer expectedDispositionVersion,
        @NotNull(message = "batchVersions 不能为 null") Map<String, Long> batchVersions
) {
}
