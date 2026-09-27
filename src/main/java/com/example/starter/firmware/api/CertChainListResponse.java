package com.example.starter.firmware.api;

import java.util.List;

/**
 * 固件版本的全部证书链视图，按链版本升序；currentChainVersion 为请求时刻唯一有效链版本，无则 null。
 */
public record CertChainListResponse(String model, String firmwareVersion, Integer currentChainVersion,
                                    List<CertChainView> chains) {
}
