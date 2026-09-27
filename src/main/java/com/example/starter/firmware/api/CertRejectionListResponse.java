package com.example.starter.firmware.api;

import java.util.List;

/**
 * 证书域拒绝记录列表响应。
 */
public record CertRejectionListResponse(List<CertRejectionView> rejections) {
}
