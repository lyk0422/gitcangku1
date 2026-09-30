package com.example.starter.cue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编排一个提示点的请求；序号在版本内从 1 开始连续且不可重复，时长 1 至 600 秒。
 *
 * @param seqNo           场次内提示点序号
 * @param cueType         提示点类型（LIGHT 灯光 / SOUND 音效）
 * @param triggerWord     触发口令
 * @param durationSeconds 持续秒数，单位：秒，范围 1 至 600
 */
public record CuePointRequest(
        @NotNull(message = "序号不能为空")
        @Min(value = 1, message = "序号必须从 1 开始")
        Integer seqNo,

        @NotBlank(message = "提示点类型不能为空")
        @Size(max = 32, message = "提示点类型长度不能超过 32")
        String cueType,

        @NotBlank(message = "触发口令不能为空")
        @Size(max = 128, message = "触发口令长度不能超过 128")
        String triggerWord,

        @NotNull(message = "持续秒数不能为空")
        @Min(value = 1, message = "持续秒数必须在 1 至 600 之间")
        @Max(value = 600, message = "持续秒数必须在 1 至 600 之间")
        Integer durationSeconds) {
}
