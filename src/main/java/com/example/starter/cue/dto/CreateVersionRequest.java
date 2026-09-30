package com.example.starter.cue.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 创建剧目提示点编排版本的请求；每次编排修改都产生一个不可变新版本。
 *
 * @param playCode 剧目编码
 * @param cues     本版本全部提示点，整版本校验，任一无效则整个版本不可创建
 */
public record CreateVersionRequest(
        @NotBlank(message = "剧目编码不能为空")
        @Size(max = 64, message = "剧目编码长度不能超过 64")
        String playCode,

        @NotEmpty(message = "提示点列表不能为空")
        @Valid
        List<CuePointRequest> cues) {
}
