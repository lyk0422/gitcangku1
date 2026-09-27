package com.example.starter.observation;

import java.time.Instant;

/**
 * 测试支撑：默认采样许可与提交请求的工厂方法。
 * 默认许可覆盖网格 [0,100]x[0,100]、UTC 有效期 [2020-01-01, 2030-01-01)、上限 100000，
 * 既有用例的提交统一落在该许可内。
 */
public final class PermitTestSupport {

    public static final String DEFAULT_PERMIT_ID = "permit-default";
    public static final String DEVICE_ID = "DEV-1";
    public static final int GRID_X = 10;
    public static final int GRID_Y = 10;
    public static final Instant COLLECTED_AT = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant VALID_FROM = Instant.parse("2020-01-01T00:00:00Z");
    public static final Instant VALID_TO = Instant.parse("2030-01-01T00:00:00Z");

    private PermitTestSupport() {
    }

    /**
     * 通过许可服务创建默认许可（占用 requestId "req-permit-setup"，每个用例清理后可重复使用）。
     */
    public static void createDefaultPermit(PermitService permitService) {
        permitService.create(new CreatePermitRequest("req-permit-setup", DEFAULT_PERMIT_ID, DEVICE_ID,
                0, 100, 0, 100, VALID_FROM, VALID_TO, 100000, 1));
    }

    /**
     * 构造落在默认许可内的提交（创建）请求。
     */
    public static CreateObservationRequest createRequest(String requestId, String observationId,
                                                         String location, String reading, String note) {
        return new CreateObservationRequest(requestId, observationId, location, reading, note,
                DEVICE_ID, GRID_X, GRID_Y, COLLECTED_AT);
    }
}
