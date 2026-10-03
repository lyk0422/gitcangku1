package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.web.dto.AssignmentResponse;
import com.example.starter.web.dto.SegmentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 幂等、撤销释放、船舶取消保留事实与时间轴查询测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SegmentStateTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM boarding_point_change");
        jdbc.update("DELETE FROM assignment");
        jdbc.update("DELETE FROM segment");
        clock.setInstant(Instant.parse("2026-10-04T08:00:00Z"));
    }

    private long createSegment(String vessel, int level, String start, String end, String point) {
        ResponseEntity<SegmentResponse> response = rest.postForEntity("/api/segments",
                Map.of("vesselCode", vessel, "requiredLevel", level, "estStartUtc", start,
                        "estEndUtc", end, "boardingPoint", point), SegmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ResponseEntity<AssignmentResponse> assign(long segmentId, String pilot, String requestId) {
        return rest.postForEntity("/api/segments/" + segmentId + "/assign",
                Map.of("pilotCode", pilot, "requestId", requestId), AssignmentResponse.class);
    }

    @Test
    void assign_sameRequestIdSameParamsReplaysFirstResult_differentParams409() {
        long seg1 = createSegment("V020", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A");
        ResponseEntity<AssignmentResponse> first = assign(seg1, "P001", "req-i1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AssignmentResponse> replay = assign(seg1, "P001", "req-i1");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().id()).isEqualTo(first.getBody().id());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM assignment", Integer.class);
        assertThat(count).isEqualTo(1);

        long seg2 = createSegment("V021", 2, "2026-10-04T10:00:00Z",
                "2026-10-04T11:00:00Z", "锚地B");
        ResponseEntity<AssignmentResponse> different = assign(seg2, "P001", "req-i1");
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelAssignment_beforeBoardingReleasesOccupation() {
        long seg1 = createSegment("V022", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A");
        assertThat(assign(seg1, "P001", "req-c1").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AssignmentResponse> cancelled = rest.postForEntity(
                "/api/segments/" + seg1 + "/cancel-assignment", null, AssignmentResponse.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("RELEASED");
        String segStatus = jdbc.queryForObject("SELECT status FROM segment WHERE id = ?",
                String.class, seg1);
        assertThat(segStatus).isEqualTo("REQUESTED");

        // 释放后同一引航员可承接重叠区间
        long seg2 = createSegment("V023", 2, "2026-10-04T09:30:00Z",
                "2026-10-04T10:30:00Z", "锚地B");
        assertThat(assign(seg2, "P001", "req-c2").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void vesselCancel_keepsBoardingFactsAndReleasesOccupation() {
        long seg = createSegment("V024", 1, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A");
        assertThat(assign(seg, "P003", "req-v1").getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        rest.postForEntity("/api/segments/" + seg + "/board",
                Map.of("requestId", "req-vb1"), AssignmentResponse.class);

        ResponseEntity<SegmentResponse> cancelled = rest.postForEntity(
                "/api/segments/" + seg + "/cancel", null, SegmentResponse.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("CANCELLED");

        // 已发生的登轮事实保留
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, boarded_at_utc, release_reason FROM assignment WHERE segment_id = ?",
                seg);
        assertThat(row.get("STATUS")).isEqualTo("RELEASED");
        assertThat(row.get("BOARDED_AT_UTC")).isNotNull();
        assertThat(row.get("RELEASE_REASON")).isEqualTo("VESSEL_CANCELLED");

        // 取消后占用解除
        long seg2 = createSegment("V025", 1, "2026-10-04T09:30:00Z",
                "2026-10-04T10:30:00Z", "锚地B");
        assertThat(assign(seg2, "P003", "req-v2").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void timeline_byPilotAndByVessel() {
        long seg1 = createSegment("V030", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A");
        long seg2 = createSegment("V030", 2, "2026-10-04T11:00:00Z",
                "2026-10-04T12:00:00Z", "锚地B");
        assertThat(assign(seg1, "P001", "req-t1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign(seg2, "P002", "req-t2").getStatusCode()).isEqualTo(HttpStatus.OK);
        rest.exchange(org.springframework.http.RequestEntity
                .put("/api/segments/" + seg1 + "/boarding-point")
                .body(Map.of("boardingPoint", "锚地A-新")), SegmentResponse.class);

        JsonNode pilotTimeline = rest.getForObject("/api/pilots/P001/timeline", JsonNode.class);
        assertThat(pilotTimeline).hasSize(1);
        assertThat(pilotTimeline.get(0).get("segment").get("vesselCode").asText()).isEqualTo("V030");
        assertThat(pilotTimeline.get(0).get("assignment").get("requestId").asText())
                .isEqualTo("req-t1");

        JsonNode vesselTimeline = rest.getForObject("/api/vessels/V030/timeline", JsonNode.class);
        assertThat(vesselTimeline).hasSize(2);
        JsonNode first = vesselTimeline.get(0);
        assertThat(first.get("segment").get("boardingPoint").asText()).isEqualTo("锚地A-新");
        JsonNode history = first.get("boardingPointHistory");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("oldPoint").asText()).isEqualTo("锚地A");
        assertThat(history.get(0).get("newPoint").asText()).isEqualTo("锚地A-新");
    }

    @Test
    void requestIdUniqueConstraintEnforcedByDatabase() {
        long seg = createSegment("V031", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A");
        assertThat(assign(seg, "P001", "req-u1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO assignment (segment_id, pilot_id, request_id, request_fingerprint,"
                        + " status, created_at_utc) VALUES (?, ?, 'req-u1', 'x', 'ACTIVE',"
                        + " CURRENT_TIMESTAMP)", seg, 1L))
                .isInstanceOf(DuplicateKeyException.class);
    }
}