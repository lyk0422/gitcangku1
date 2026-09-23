package com.example.starter.api.dto;

/**
 * BLOCKED 结论中单个命中区域的豁免缺口。
 *
 * @param regionKey     命中区域标识
 * @param regionVersion 命中区域的生效空域版本
 * @param permitKey     被评估的豁免包标识；不存在任何候选包时为 null
 * @param reason        MISSING / EXPIRED / EXHAUSTED
 */
public record RegionDeficit(String regionKey, long regionVersion, String permitKey,
                            String reason) {
}
