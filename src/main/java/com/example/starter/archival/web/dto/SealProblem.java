package com.example.starter.archival.web.dto;

/**
 * 封存校验发现的问题页。整批封存时只要存在任一问题页即整批拒绝，不得只封存合格子集。
 *
 * @param accessionNo 问题页馆藏号
 * @param reason      问题原因：读数缺失/超范围/处理后低于处理前
 */
public record SealProblem(String accessionNo, String reason) {
}
