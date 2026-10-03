package com.example.starter.well;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 井口压力采样，只追加不修改；错误读数通过作废标记与替代读数更正。
 *
 * @param id 采样主键
 * @param wellNo 井号
 * @param requestId 幂等请求编号，同井唯一
 * @param payloadHash 请求参数摘要
 * @param pressureMpa 井口压力，单位 MPa
 * @param sampledAt 采样时刻（UTC）
 * @param voided 是否已作废，作废读数不参与切换条件判定
 * @param voidReason 作废原因，未作废为 null
 * @param replacedById 替代读数采样主键，未作废为 null
 */
public record PressureSample(long id, String wellNo, String requestId, String payloadHash,
                             BigDecimal pressureMpa, Instant sampledAt, boolean voided,
                             String voidReason, Long replacedById) {
}