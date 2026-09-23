package com.example.starter.api.dto;

import java.util.List;

/**
 * 单个命中区域的核销余额快照（扣减前、扣减后，单位次）。
 *
 * @param regionKey     区域标识
 * @param regionVersion 区域版本
 * @param before        扣减前剩余额度
 * @param after         扣减后剩余额度
 */
public record ConsumedQuota(String regionKey, long regionVersion, int before, int after) {
}
