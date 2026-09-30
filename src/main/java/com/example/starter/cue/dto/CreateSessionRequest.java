package com.example.starter.cue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建排练场次请求；新场次为未开始状态，可选择立即绑定一个编排版本。
 *
 * @param playCode    剧目编码
 * @param sessionCode 场次业务编码，同剧目内唯一
 * @param versionNo   绑定的版本号，为空表示暂不绑定（之后可绑定最新版本）
 */
public record CreateSessionRequest(
        @NotBlank(message = "剧目编码不能为空")
        @Size(max = 64, message = "剧目编码长度不能超过 64")
        String playCode,

        @NotBlank(message = "场次编码不能为空")
        @Size(max = 64, message = "场次编码长度不能超过 64")
        String sessionCode,

        Integer versionNo) {
}
