package com.example.starter.deacid;

/**
 * 封存校验发现的问题页。
 *
 * @param collectionNo 馆藏号
 * @param reason       问题原因（缺读数、超范围、处理后低于处理前、跨批次占用）
 */
public record ProblemPage(String collectionNo, String reason) {
}