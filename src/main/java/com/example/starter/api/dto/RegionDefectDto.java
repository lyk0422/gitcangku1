package com.example.starter.api.dto;

/**
 * BLOCKED 时某个命中区域无法被豁免核销的缺陷项。
 *
 * @param regionKey 命中区域标识
 * @param reason    原因：MISSING 豁免包缺项 / VERSION_MISMATCH 区域版本不匹配 /
 *                  REVOKED 豁免已撤销 / EXPIRED 超出 UTC 有效区间 / EXHAUSTED 剩余额度为 0
 * @param detail    可读细节（如版本号、区间、余额），仅用于排错，不参与判定
 */
public record RegionDefectDto(String regionKey, String reason, String detail) {
}
