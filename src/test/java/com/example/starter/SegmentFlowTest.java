package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.web.dto.AssignmentResponse;
import com.example.starter.web.dto.ErrorResponse;
import com.example.starter.web.dto.SegmentResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 主流程与拒绝分支测试：真实 H2 写入，覆盖申请、指派、登轮、离轮与登轮点变更。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SegmentFlowTest {

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

    private ResponseEntity<SegmentResponse> createSegment(String vessel, int level, String start,
                                                          String end, String point) {
        return rest.postForEntity("/api/segments", Map.of("vesselCode", vessel,
                "requiredLevel", level, "estStartUtc", start, "estEndUtc", end,
                "boardingPoint", point), SegmentResponse.class);
    }

    private ResponseEntity<AssignmentResponse> assign(long segmentId, String pilot, String requestId) {
        return rest.postForEntity("/api/segments/" + segmentId + "/assign",
                Map.of("pilotCode", pilot, "requestId", requestId), AssignmentResponse.class);
    }

    private ResponseEntity<AssignmentResponse> board(long segmentId, String requestId) {
        return rest.postForEntity("/api/segments/" + segmentId + "/board",
                Map.of("requestId", requestId), AssignmentResponse.class);
    }

    @Test
    void fullFlow_occupationExtendsUntilActualDisembark() {
        ResponseEntity<SegmentResponse> created = createSegment("V001", 2,
                "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long seg1 = created.getBody().id();
        assertThat(created.getBody().status()).isEqualTo("REQUESTED");

        ResponseEntity<AssignmentResponse> assigned = assign(seg1, "P002", "req-a1");
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().status()).isEqualTo("ACTIVE");

        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        ResponseEntity<AssignmentResponse> boarded = board(seg1, "req-b1");
        assertThat(boarded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(boarded.getBody().boardedAtUtc())
                .isEqualTo(Instant.parse("2026-10-04T09:05:00Z"));

        // 已登轮未离轮：预计区间 10:00 结束也不释放，实际 10:10 离轮之前仍占用
        long seg2 = createSegment("V002", 2, "2026-10-04T10:00:00Z",
                "2026-10-04T11:00:00Z", "锚地B").getBody().id();
        ResponseEntity<AssignmentResponse> conflict = assign(seg2, "P002", "req-a2");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<AssignmentResponse> disembarked = rest.postForEntity(
                "/api/segments/" + seg1 + "/disembark",
                Map.of("disembarkedAtUtc", "2026-10-04T10:10:00Z"), AssignmentResponse.class);
        assertThat(disembarked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disembarked.getBody().status()).isEqualTo("COMPLETED");
        assertThat(disembarked.getBody().disembarkedAtUtc())
                .isEqualTo(Instant.parse("2026-10-04T10:10:00Z"));

        // 10:00-11:00 与实际离轮 10:10 重叠，仍 409；10:10 起端点相接可指派
        assertThat(assign(seg2, "P002", "req-a2").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        long seg3 = createSegment("V003", 2, "2026-10-04T10:10:00Z",
                "2026-10-04T11:00:00Z", "锚地C").getBody().id();
        assertThat(assign(seg3, "P002", "req-a3").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createSegment_reversedRangeReturns422AndPersistsNothing() {
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/segments",
                Map.of("vesselCode", "V010", "requiredLevel", 1,
                        "estStartUtc", "2026-10-04T10:00:00Z",
                        "estEndUtc", "2026-10-04T09:00:00Z",
                        "boardingPoint", "锚地A"), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("INVALID_TIME_RANGE");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM segment", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void assign_insufficientQualificationReturns422AndRollsBack() {
        long seg = createSegment("V011", 3, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A").getBody().id();
        ResponseEntity<AssignmentResponse> response = assign(seg, "P002", "req-q1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Integer assignments = jdbc.queryForObject("SELECT COUNT(*) FROM assignment", Integer.class);
        assertThat(assignments).isZero();
        String status = jdbc.queryForObject("SELECT status FROM segment WHERE id = ?",
                String.class, seg);
        assertThat(status).isEqualTo("REQUESTED");
    }
    @Test
    void assign_overlappingEstimatedIntervalReturns409AndFailureKeepsNoRequestKey() {
        long seg1 = createSegment("V012", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A").getBody().id();
        assertThat(assign(seg1, "P001", "req-o1").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 09:30 落入已指派的 09:00-10:00 预计区间，返回 409
        long seg2 = createSegment("V013", 2, "2026-10-04T09:30:00Z",
                "2026-10-04T10:30:00Z", "锚地B").getBody().id();
        ResponseEntity<AssignmentResponse> conflict = assign(seg2, "P001", "req-o2");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 失败不占请求键：同一 requestId 用于不重叠的航段可以成功
        long seg3 = createSegment("V014", 2, "2026-10-04T10:00:00Z",
                "2026-10-04T11:00:00Z", "锚地C").getBody().id();
        assertThat(assign(seg3, "P001", "req-o2").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void boardingPointChange_onlyBeforeBoardingAndKeepsHistory() {
        long seg = createSegment("V015", 1, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A").getBody().id();
        ResponseEntity<SegmentResponse> changed = rest.exchange(
                org.springframework.http.RequestEntity.put("/api/segments/" + seg + "/boarding-point")
                        .body(Map.of("boardingPoint", "锚地B")), SegmentResponse.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().boardingPoint()).isEqualTo("锚地B");
        String oldPoint = jdbc.queryForObject(
                "SELECT old_point FROM boarding_point_change WHERE segment_id = ?",
                String.class, seg);
        assertThat(oldPoint).isEqualTo("锚地A");

        assertThat(assign(seg, "P003", "req-p1").getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        assertThat(board(seg, "req-pb1").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ErrorResponse> rejected = rest.exchange(
                org.springframework.http.RequestEntity.put("/api/segments/" + seg + "/boarding-point")
                        .body(Map.of("boardingPoint", "锚地C")), ErrorResponse.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String current = jdbc.queryForObject("SELECT boarding_point FROM segment WHERE id = ?",
                String.class, seg);
        assertThat(current).isEqualTo("锚地B");
    }

    @Test
    void board_idempotentReplayAndStateGuards() {
        long seg = createSegment("V016", 2, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A").getBody().id();
        assertThat(assign(seg, "P002", "req-s1").getStatusCode()).isEqualTo(HttpStatus.OK);

        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        ResponseEntity<AssignmentResponse> first = board(seg, "req-sb1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同参重放返回首次结果
        ResponseEntity<AssignmentResponse> replay = board(seg, "req-sb1");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().boardedAtUtc()).isEqualTo(first.getBody().boardedAtUtc());

        // 异参（不同 requestId）重复登轮返回 409
        assertThat(board(seg, "req-sb2").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 已登轮只能确认离轮，撤销返回 409 且不释放占用
        ResponseEntity<AssignmentResponse> cancel = rest.postForEntity(
                "/api/segments/" + seg + "/cancel-assignment", null, AssignmentResponse.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String status = jdbc.queryForObject(
                "SELECT status FROM assignment WHERE segment_id = ?", String.class, seg);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void disembark_beforeBoardingTimeReturns422() {
        long seg = createSegment("V017", 1, "2026-10-04T09:00:00Z",
                "2026-10-04T10:00:00Z", "锚地A").getBody().id();
        assertThat(assign(seg, "P003", "req-d1").getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        assertThat(board(seg, "req-db1").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AssignmentResponse> response = rest.postForEntity(
                "/api/segments/" + seg + "/disembark",
                Map.of("disembarkedAtUtc", "2026-10-04T09:00:00Z"), AssignmentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        String status = jdbc.queryForObject(
                "SELECT status FROM assignment WHERE segment_id = ?", String.class, seg);
        assertThat(status).isEqualTo("ACTIVE");
    }
}