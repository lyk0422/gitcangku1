package com.example.starter.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保养完成、锚点规则与 DUE/OK 状态判定测试。
 */
class MaintenanceFlowTest extends AbstractApiTest {

    @BeforeEach
    void registerEquipment() throws Exception {
        var result = postJson("/api/equipment", registerEquipment("req-reg", "EQ-1", 100));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void addReadingOk(String requestId, int version, String readingId, String sampledAt, long minutes)
            throws Exception {
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading(requestId, version, readingId, sampledAt, minutes));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeMaintenanceFirstCanUseAnyReading() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 30);
        addReadingOk("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 80);

        var result = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-3", 3, "R-1", 1));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var json = body(result);
        assertThat(json.get("anchorReadingId").asText()).isEqualTo("R-1");
        assertThat(json.get("anchorRevisionNo").asInt()).isEqualTo(1);
        assertThat(json.get("anchorAccumulatedMinutes").asLong()).isEqualTo(30);
        assertThat(json.get("anchorSampledAt").asText()).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    void completeMaintenanceAnchorMustBeLaterThanLast() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 30);
        addReadingOk("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 80);
        var first = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-3", 3, "R-2", 1));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // 锚点早于上次保养锚点 -> 422
        var earlier = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-4", 4, "R-1", 1));
        assertThat(earlier.getResponse().getStatus()).isEqualTo(422);
        // 锚点等于上次保养锚点 -> 422
        var same = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-5", 4, "R-2", 1));
        assertThat(same.getResponse().getStatus()).isEqualTo(422);

        addReadingOk("req-6", 4, "R-3", "2026-01-01T03:00:00Z", 120);
        var later = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-7", 5, "R-3", 1));
        assertThat(later.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeMaintenanceAnchorRevisionMismatch() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 30);
        addReadingOk("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 80);
        var revised = postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-3", 3, 35));
        assertThat(revised.getResponse().getStatus()).isEqualTo(200);

        // 锚点修订号已过期 -> 409
        var stale = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-4", 4, "R-1", 1));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        // 使用当前修订号 -> 201，快照为修订后的值
        var current = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-5", 4, "R-1", 2));
        assertThat(current.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(current).get("anchorAccumulatedMinutes").asLong()).isEqualTo(35);
    }

    @Test
    void completeMaintenanceMissingAnchorNotFound() throws Exception {
        var result = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-1", 1, "R-9", 1));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anchoredReadingCannotBeRevised() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 30);
        var done = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-2", 2, "R-1", 1));
        assertThat(done.getResponse().getStatus()).isEqualTo(201);

        var revise = postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-3", 3, 35));
        assertThat(revise.getResponse().getStatus()).isEqualTo(409);

        // 历史保养锚点同样不可修订：第二次保养后，旧锚点仍被锁定
        addReadingOk("req-4", 3, "R-2", "2026-01-01T02:00:00Z", 80);
        var second = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-5", 4, "R-2", 1));
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        var reviseOld = postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-6", 5, 36));
        assertThat(reviseOld.getResponse().getStatus()).isEqualTo(409);
        var reviseNew = postJson("/api/equipment/EQ-1/readings/R-2/revisions", reviseReading("req-7", 5, 81));
        assertThat(reviseNew.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void statusTransitionsFromOkToDue() throws Exception {
        var initial = body(getJson("/api/equipment/EQ-1/status"));
        assertThat(initial.get("status").asText()).isEqualTo("OK");
        assertThat(initial.get("runningMinutes").asLong()).isEqualTo(0);

        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 99);
        assertThat(body(getJson("/api/equipment/EQ-1/status")).get("status").asText()).isEqualTo("OK");

        addReadingOk("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 100);
        var due = body(getJson("/api/equipment/EQ-1/status"));
        assertThat(due.get("status").asText()).isEqualTo("DUE");
        assertThat(due.get("runningMinutes").asLong()).isEqualTo(100);
    }

    @Test
    void runningMinutesResetFromLastMaintenanceAnchor() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 150);
        assertThat(body(getJson("/api/equipment/EQ-1/status")).get("status").asText()).isEqualTo("DUE");

        var done = postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-2", 2, "R-1", 1));
        assertThat(done.getResponse().getStatus()).isEqualTo(201);

        var afterMaintenance = body(getJson("/api/equipment/EQ-1/status"));
        assertThat(afterMaintenance.get("status").asText()).isEqualTo("OK");
        assertThat(afterMaintenance.get("runningMinutes").asLong()).isEqualTo(0);
        assertThat(afterMaintenance.get("lastMaintenanceAnchorMinutes").asLong()).isEqualTo(150);

        addReadingOk("req-3", 3, "R-2", "2026-01-01T02:00:00Z", 240);
        var running = body(getJson("/api/equipment/EQ-1/status"));
        assertThat(running.get("runningMinutes").asLong()).isEqualTo(90);
        assertThat(running.get("status").asText()).isEqualTo("OK");

        addReadingOk("req-4", 4, "R-3", "2026-01-01T03:00:00Z", 250);
        assertThat(body(getJson("/api/equipment/EQ-1/status")).get("status").asText()).isEqualTo("DUE");
    }

    @Test
    void historyContainsReadingsRevisionsAndMaintenances() throws Exception {
        addReadingOk("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 30);
        postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-2", 2, 35));
        postJson("/api/equipment/EQ-1/maintenances", completeMaintenance("req-3", 3, "R-1", 2));

        var history = body(getJson("/api/equipment/EQ-1/history"));
        assertThat(history.get("equipmentId").asText()).isEqualTo("EQ-1");
        assertThat(history.get("maintenanceIntervalMinutes").asInt()).isEqualTo(100);
        assertThat(history.get("version").asInt()).isEqualTo(4);
        assertThat(history.get("readings")).hasSize(1);
        var reading = history.get("readings").get(0);
        assertThat(reading.get("maintenanceAnchor").asBoolean()).isTrue();
        assertThat(reading.get("revisions")).hasSize(2);
        assertThat(history.get("maintenances")).hasSize(1);
        assertThat(history.get("maintenances").get(0).get("anchorRevisionNo").asInt()).isEqualTo(2);
    }
}
