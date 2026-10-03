package com.example.starter.service;

import com.example.starter.domain.PressureSample;

/**
 * 采样作废结果。
 *
 * @param voidedSample 被追加作废标记的原采样
 * @param replacement 追加的替代读数
 */
public record VoidResult(PressureSample voidedSample, PressureSample replacement) {
}