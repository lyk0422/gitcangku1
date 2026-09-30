package com.example.starter.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.service.BerthAllocationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端 HTTP 测试：真实 JSON 序列化与 201/422/409 状态码，复现题干主场景。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BerthApiHttpTest {

    private static final LocalDateTime T06 = LocalDateTime.of(2026, 10, 1, 6, 0);
    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);
    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime T12 = LocalDateTime.of(2026, 10, 1, 12, 0);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BerthAllocationService service;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM review_record");
        jdbcTemplate.update("DELETE FROM berth_application");
        jdbcTemplate.update("DELETE FROM tide_segment");
        jdbcTemplate.update("DELETE FROM tide_version");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void endToEnd_confirmThenRevisionPendingThenTimelineAndConflicts() {
        // 1. 发布潮高版本 v1：08-10 最低潮高 0.7
        TidePublishRequest publish = new TidePublishRequest(T06, T12, List.of(
                new TidePublishRequest.Segment(T06, T08, new BigDecimal("1.20")),
                new TidePublishRequest.Segment(T08, T10, new BigDecimal("0.70")),
                new TidePublishRequest.Segment(T10, T12, new BigDecimal("1.00"))));
        ResponseEntity<TideVersionResponse> published = restTemplate.postForEntity(
                url("/api/berths/tide-versions"), publish, TideVersionResponse.class);
        assertEquals(HttpStatus.CREATED, published.getStatusCode());
        assertEquals(1L, published.getBody().versionNo());

        // 2. 9.2 米合成船申请 08:00-10:00：8.8+0.7=9.5 >= 9.4，确认
        ApplyRequest apply = new ApplyRequest("REQ-HTTP", "合成船9.2",
                new BigDecimal("9.20"), new BigDecimal("0.20"), "B-01", T08, T10);
        ResponseEntity<ApplicationResponse> created = restTemplate.postForEntity(
                url("/api/berths/applications"), apply, ApplicationResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        ApplicationResponse body = created.getBody();
        assertEquals("CONFIRMED", body.status());
        assertEquals(0, new BigDecimal("0.70").compareTo(body.minTideHeight()));
        assertEquals(0, new BigDecimal("0.10").compareTo(body.clearance()));

        // 3. 同参重放幂等
        ResponseEntity<ApplicationResponse> replay = restTemplate.postForEntity(
                url("/api/berths/applications"), apply, ApplicationResponse.class);
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals("CONFIRMED", replay.getBody().status());

        // 4. 异参 409
        ApplyRequest conflictReq = new ApplyRequest("REQ-HTTP", "合成船9.2",
                new BigDecimal("8.00"), new BigDecimal("0.20"), "B-01", T08, T10);
        ResponseEntity<String> conflict = restTemplate.postForEntity(
                url("/api/berths/applications"), conflictReq, String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 5. 修订潮高 0.7 -> 0.5
        TidePublishRequest revision = new TidePublishRequest(T06, T12, List.of(
                new TidePublishRequest.Segment(T06, T08, new BigDecimal("0.80")),
                new TidePublishRequest.Segment(T08, T10, new BigDecimal("0.50")),
                new TidePublishRequest.Segment(T10, T12, new BigDecimal("0.30"))));
        restTemplate.postForEntity(url("/api/berths/tide-versions"), revision,
                TideVersionResponse.class);

        // 6. 申请详情：待复核，保留 v1 快照
        ResponseEntity<ApplicationResponse> detail = restTemplate.getForEntity(
                url("/api/berths/applications/REQ-HTTP"), ApplicationResponse.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        assertEquals("PENDING_REVIEW", detail.getBody().status());
        assertEquals(2L, detail.getBody().tideVersionNo());
        assertEquals(1L, detail.getBody().snapTideVersionNo());

        // 7. 泊位时间轴
        ResponseEntity<BerthTimelineResponse> timeline = restTemplate.getForEntity(
                url("/api/berths/B-01/timeline"), BerthTimelineResponse.class);
        assertEquals(HttpStatus.OK, timeline.getStatusCode());
        assertNotNull(timeline.getBody().currentTideVersion());
        assertEquals(2L, timeline.getBody().currentTideVersion().versionNo());
        assertEquals(1, timeline.getBody().applications().size());

        // 8. 潮高缺口 422
        TidePublishRequest gapped = new TidePublishRequest(T06, T12, List.of(
                new TidePublishRequest.Segment(T06, T08, new BigDecimal("1.00")),
                new TidePublishRequest.Segment(T08.plusMinutes(30), T12, new BigDecimal("1.00"))));
        ResponseEntity<String> gap = restTemplate.postForEntity(
                url("/api/berths/tide-versions"), gapped, String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, gap.getStatusCode());
    }
}
