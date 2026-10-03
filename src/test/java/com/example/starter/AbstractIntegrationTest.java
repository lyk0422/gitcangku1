package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.repo.RunSegmentRepository;
import com.example.starter.repo.SwitchRequestRepository;
import com.example.starter.service.WellService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ MockMvc + 可控时钟；每个用例前清空场景数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

    /** 可控时钟的基准时刻（UTC）。 */
    protected static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected WellService wellService;
    @Autowired
    protected RunSegmentRepository segmentRepo;
    @Autowired
    protected SwitchRequestRepository switchRepo;

    @MockitoBean
    protected Clock clock;

    /** 当前业务时刻，用例可推进。 */
    protected final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM switch_request_sample");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM switch_request");
        jdbc.update("DELETE FROM pressure_sample");
        jdbc.update("DELETE FROM run_segment");
        jdbc.update("DELETE FROM well_lock");
        now.set(T0);
        Mockito.lenient().when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        Mockito.lenient().when(clock.millis()).thenAnswer(invocation -> now.get().toEpochMilli());
    }

    /** POST 并断言状态码，返回响应 JSON。 */
    protected JsonNode callPost(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** GET 时间线。 */
    protected JsonNode timeline(String wellNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/wells/{wellNo}/timeline", wellNo))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 采样请求体。 */
    protected Map<String, Object> sampleBody(String requestId, String pressureMpa, Instant sampledAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("pressureMpa", pressureMpa);
        body.put("sampledAt", sampledAt.toString());
        return body;
    }

    /** 创建 INJECTION 运行段（起点 T0 前 1 小时）。 */
    protected long createInjectionSegment(String wellNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "seg-1");
        body.put("mode", "INJECTION");
        body.put("startTime", T0.minus(1, ChronoUnit.HOURS).toString());
        JsonNode json = callPost("/api/wells/" + wellNo + "/run-segments", body, 201);
        return json.get("id").asLong();
    }

    /** 在 T0 前 12/9/6/3 分钟各追加一个 10.5 MPa 有效采样，返回采样编号。 */
    protected List<Long> addFourValidSamples(String wellNo) throws Exception {
        List<Long> ids = new ArrayList<>();
        int[] minutesBefore = {12, 9, 6, 3};
        for (int i = 0; i < minutesBefore.length; i++) {
            JsonNode json = callPost("/api/wells/" + wellNo + "/samples",
                    sampleBody("smp-" + (i + 1), "10.5", T0.minus(minutesBefore[i], ChronoUnit.MINUTES)), 201);
            ids.add(json.get("id").asLong());
        }
        return ids;
    }

    /** 创建待审核切换申请，返回申请编号。 */
    protected long createPendingSwitch(String wellNo, String requestId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("targetMode", "PRODUCTION");
        JsonNode json = callPost("/api/wells/" + wellNo + "/switch-requests", body, 201);
        return json.get("id").asLong();
    }
}