package com.example.starter.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工时读数新增、补录、单调性与修订规则测试。
 */
class ReadingFlowTest extends AbstractApiTest {

    @BeforeEach
    void registerEquipment() throws Exception {
        var result = postJson("/api/equipment", registerEquipment("req-reg", "EQ-1", 100));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void addReadingCreatedAndBumpsVersion() throws Exception {
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 50));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var json = body(result);
        assertThat(json.get("readingId").asText()).isEqualTo("R-1");
        assertThat(json.get("accumulatedMinutes").asLong()).isEqualTo(50);
        assertThat(json.get("currentRevision").asInt()).isEqualTo(1);

        var history = getJson("/api/equipment/EQ-1/history");
        assertThat(body(history).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void addReadingBackfillMustFitBothNeighbors() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        postJson("/api/equipment/EQ-1/readings", addReading("req-2", 2, "R-2", "2026-01-01T03:00:00Z", 50));

        // 补录早于所有读数、值合法
        var early = postJson("/api/equipment/EQ-1/readings",
                addReading("req-3", 3, "R-0", "2026-01-01T00:30:00Z", 10));
        assertThat(early.getResponse().getStatus()).isEqualTo(201);

        // 补录中间点：小于前邻 -> 422
        var belowPrev = postJson("/api/equipment/EQ-1/readings",
                addReading("req-4", 4, "R-X", "2026-01-01T02:00:00Z", 15));
        assertThat(belowPrev.getResponse().getStatus()).isEqualTo(422);
        // 补录中间点：大于后邻 -> 422（证明不只与最新值比较）
        var aboveNext = postJson("/api/equipment/EQ-1/readings",
                addReading("req-5", 4, "R-X", "2026-01-01T02:00:00Z", 60));
        assertThat(aboveNext.getResponse().getStatus()).isEqualTo(422);
        // 补录中间点：落在前后邻之间 -> 201
        var fits = postJson("/api/equipment/EQ-1/readings",
                addReading("req-6", 4, "R-X", "2026-01-01T02:00:00Z", 30));
        assertThat(fits.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void addReadingSameTimestampRejected() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading("req-2", 2, "R-2", "2026-01-01T01:00:00Z", 20));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void addReadingDuplicateReadingIdConflict() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading("req-2", 2, "R-1", "2026-01-01T02:00:00Z", 30));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void addReadingVersionConflict() throws Exception {
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading("req-1", 5, "R-1", "2026-01-01T01:00:00Z", 20));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void addReadingNegativeMinutesRejected() throws Exception {
        var result = postJson("/api/equipment/EQ-1/readings",
                addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", -1));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void reviseReadingKeepsHistoryAndBumpsRevision() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        postJson("/api/equipment/EQ-1/readings", addReading("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 40));

        clock.advance(java.time.Duration.ofMinutes(5));
        var revised = postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-3", 3, 25));
        assertThat(revised.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(revised).get("currentRevision").asInt()).isEqualTo(2);

        var history = body(getJson("/api/equipment/EQ-1/history"));
        var reading = history.get("readings").get(0);
        assertThat(reading.get("readingId").asText()).isEqualTo("R-1");
        assertThat(reading.get("accumulatedMinutes").asLong()).isEqualTo(25);
        assertThat(reading.get("sampledAt").asText()).isEqualTo("2026-01-01T01:00:00Z");
        var revisions = reading.get("revisions");
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).get("accumulatedMinutes").asLong()).isEqualTo(20);
        assertThat(revisions.get(1).get("accumulatedMinutes").asLong()).isEqualTo(25);
        assertThat(revisions.get(1).get("revisedAt").asText()).isEqualTo("2026-01-01T00:05:00Z");
    }

    @Test
    void reviseReadingMustFitNeighbors() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        postJson("/api/equipment/EQ-1/readings", addReading("req-2", 2, "R-2", "2026-01-01T02:00:00Z", 40));
        postJson("/api/equipment/EQ-1/readings", addReading("req-3", 3, "R-3", "2026-01-01T03:00:00Z", 60));

        var belowPrev = postJson("/api/equipment/EQ-1/readings/R-2/revisions", reviseReading("req-4", 4, 10));
        assertThat(belowPrev.getResponse().getStatus()).isEqualTo(422);
        var aboveNext = postJson("/api/equipment/EQ-1/readings/R-2/revisions", reviseReading("req-5", 4, 70));
        assertThat(aboveNext.getResponse().getStatus()).isEqualTo(422);
        var fits = postJson("/api/equipment/EQ-1/readings/R-2/revisions", reviseReading("req-6", 4, 45));
        assertThat(fits.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reviseMissingReadingNotFound() throws Exception {
        var result = postJson("/api/equipment/EQ-1/readings/R-9/revisions", reviseReading("req-1", 1, 10));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reviseVersionConflict() throws Exception {
        postJson("/api/equipment/EQ-1/readings", addReading("req-1", 1, "R-1", "2026-01-01T01:00:00Z", 20));
        var result = postJson("/api/equipment/EQ-1/readings/R-1/revisions", reviseReading("req-2", 1, 25));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }
}
