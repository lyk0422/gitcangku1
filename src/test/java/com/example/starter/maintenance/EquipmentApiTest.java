package com.example.starter.maintenance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备登记与幂等键语义测试。
 */
class EquipmentApiTest extends AbstractApiTest {

    @Test
    void registerEquipmentCreated() throws Exception {
        var result = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var json = body(result);
        assertThat(json.get("equipmentId").asText()).isEqualTo("EQ-1");
        assertThat(json.get("maintenanceIntervalMinutes").asInt()).isEqualTo(100);
        assertThat(json.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void registerEquipmentDuplicateIdConflict() throws Exception {
        postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        var result = postJson("/api/equipment", registerEquipment("req-2", "EQ-1", 200));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerEquipmentInvalidIntervalRejected() throws Exception {
        var zero = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 0));
        assertThat(zero.getResponse().getStatus()).isEqualTo(400);
        var negative = postJson("/api/equipment", registerEquipment("req-2", "EQ-1", -5));
        assertThat(negative.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void sameRequestIdSameParamsReplaysOriginalSuccess() throws Exception {
        var first = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        var replay = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(content(replay)).isEqualTo(content(first));
        // 重放不产生重复设备，版本仍为1
        var status = getJson("/api/equipment/EQ-1/status");
        assertThat(status.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(status).get("maintenanceIntervalMinutes").asInt()).isEqualTo(100);
    }

    @Test
    void sameRequestIdDifferentParamsConflict() throws Exception {
        postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        var result = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 200));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        var otherAction = postJson("/api/equipment/EQ-1/readings",
                addReading("req-1", 1, "R-1", "2026-01-01T00:01:00Z", 10));
        assertThat(otherAction.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        // 参数校验失败（400），不占键
        var bad = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", -1));
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        var good = postJson("/api/equipment", registerEquipment("req-1", "EQ-1", 100));
        assertThat(good.getResponse().getStatus()).isEqualTo(201);

        // 业务失败（版本冲突409），不占键
        var conflict = postJson("/api/equipment/EQ-1/readings",
                addReading("req-2", 99, "R-1", "2026-01-01T00:01:00Z", 10));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        var retry = postJson("/api/equipment/EQ-1/readings",
                addReading("req-2", 1, "R-1", "2026-01-01T00:01:00Z", 10));
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void statusOfMissingEquipmentNotFound() throws Exception {
        var result = getJson("/api/equipment/NOPE/status");
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
