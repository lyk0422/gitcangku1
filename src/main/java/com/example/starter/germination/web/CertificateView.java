package com.example.starter.germination.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 证书视图；证书固化的检测编号可直接看见。
 */
public record CertificateView(String certNo,
                              String batchNo,
                              String firstTestNo,
                              BigDecimal firstRatio,
                              String secondTestNo,
                              BigDecimal secondRatio,
                              String status,
                              Instant issuedAt) {
}
