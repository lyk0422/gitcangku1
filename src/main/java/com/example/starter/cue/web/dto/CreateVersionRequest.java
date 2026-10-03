package com.example.starter.cue.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 新建编排版本请求：每次修改产生一个新版本。
 *
 * @param production 剧目名称
 * @param cues       全部提示点
 */
public record CreateVersionRequest(@NotBlank String production,
                                   @NotEmpty List<@Valid CueInput> cues) {
}