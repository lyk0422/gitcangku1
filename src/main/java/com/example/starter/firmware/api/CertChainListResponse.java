package com.example.starter.firmware.api;

import java.util.List;

/**
 * 证书链列表响应。
 */
public record CertChainListResponse(List<CertChainView> chains) {
}
