package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 站台能力的指定历史版本视图，用于回溯历史发布当时的站台能力。
 */
public record PlatformCapabilityView(String platformId, int version, int maxLengthMeters,
                                     List<String> powerSystems, boolean accessible) {
}
