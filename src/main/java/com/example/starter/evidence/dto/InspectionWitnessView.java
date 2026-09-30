package com.example.starter.evidence.dto;

import com.example.starter.evidence.SealResult;

import java.time.LocalDateTime;

/**
 * 巡检见证人视图。
 *
 * @param witnessId 见证保管人标识
 * @param sealResult 该见证人给出的封条结论
 * @param note      见证备注；null 表示未填写
 * @param createdAt 见证提交时间（Asia/Shanghai）
 */
public record InspectionWitnessView(
        String witnessId,
        SealResult sealResult,
        String note,
        LocalDateTime createdAt) {
}
