package com.example.starter.cue.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新建版本时的提示点输入。持续秒数不在此做范围校验，
 * 由冻结时整体校验，保证任何无效值都使整个版本不能冻结。
 *
 * @param seq             场次内递增序号
 * @param cueType         提示点类型：LIGHT / SOUND
 * @param triggerPhrase   触发口令
 * @param durationSeconds 持续秒数，冻结时校验 1 至 600
 */
public record CueInput(@NotNull Integer seq,
                       @NotBlank String cueType,
                       @NotBlank String triggerPhrase,
                       @NotNull Integer durationSeconds) {
}