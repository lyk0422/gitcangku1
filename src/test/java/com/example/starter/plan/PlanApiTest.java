package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 铁路走廊日计划 API 单元测试：主流程、失败分支、幂等重放与并发发布边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanApiTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlanService planService;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String key(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private static Instant sh(int hour, int minute) {
        return DAY.atTime(LocalTime.of(hour, minute)).atZone(PlanService.OPERATING_ZONE).toInstant();
    }

    private static OccupancyInput occ(String train, String section, int startHour, int endHour) {
        return new OccupancyInput(train, section, sh(startHour, 0), sh(endHour, 0));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult createPlan(String scheduleKey, String requestKey, List<OccupancyInput> occupancies)
            throws Exception {
        return mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(requestKey, scheduleKey, DAY, occupancies))))
                .andReturn();
    }

    private MvcResult publish(String scheduleKey, String requestKey) throws Exception {
        return mockMvc.perform(post("/api/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(requestKey))))
                .andReturn();
    }

    @Test
    void createDraftThenGetDetail() throws Exception {
        String scheduleKey = key("SCH-CREATE");
        createPlan(scheduleKey, key("RK"), List.of(occ("G101", "SEC-A", 8, 10)))
                .getResponse().getStatus();

        mockMvc.perform(get("/api/plans/{key}", scheduleKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleKey").value(scheduleKey))
                .andExpect(jsonPath("$.operatingDate").value("2026-09-21"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.occupancies[0].trainNo").value("G101"))
                .andExpect(jsonPath("$.occupancies[0].sectionId").value("SEC-A"))
                .andExpect(jsonPath("$.occupancies[0].startUtc").value("2026-09-21T00:00:00Z"))
                .andExpect(jsonPath("$.occupancies[0].endUtc").value("2026-09-21T02:00:00Z"));
    }

    @Test
    void createReturns201() throws Exception {
        MvcResult result = createPlan(key("SCH-201"), key("RK"), List.of(occ("G1", "SEC-201", 8, 9)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void createRejectsInvalidTimeRange() throws Exception {
        // 结束等于开始 -> 400
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-BAD-T"), DAY,
                                List.of(new OccupancyInput("G1", "SEC-X", sh(9, 0), sh(9, 0)))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // 结束早于开始 -> 400
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-BAD-T2"), DAY,
                                List.of(new OccupancyInput("G1", "SEC-X", sh(10, 0), sh(9, 0)))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsOccupancyOutsideOperatingDay() throws Exception {
        // 起止跨出运营日（Asia/Shanghai）-> 400
        Instant start = DAY.minusDays(1).atTime(23, 0).atZone(PlanService.OPERATING_ZONE).toInstant();
        Instant end = DAY.atTime(1, 0).atZone(PlanService.OPERATING_ZONE).toInstant();
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-BAD-D"), DAY,
                                List.of(new OccupancyInput("G1", "SEC-X", start, end))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAcceptsOccupancyEndingAtDayBoundary() throws Exception {
        // 结束恰好为运营日末 24:00（右开）-> 合法
        Instant end = DAY.plusDays(1).atStartOfDay(PlanService.OPERATING_ZONE).toInstant();
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-BOUNDARY"), DAY,
                                List.of(new OccupancyInput("G1", "SEC-BOUNDARY", sh(22, 0), end))))))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsEmptyAndOversizedOccupancyList() throws Exception {
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-EMPTY"), DAY, List.of()))))
                .andExpect(status().isBadRequest());

        List<OccupancyInput> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            tooMany.add(new OccupancyInput("G" + i, "SEC-FULL-" + i, sh(8, 0), sh(9, 0)));
        }
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), key("SCH-FULL"), DAY, tooMany))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"\",\"scheduleKey\":\"\",\"operatingDate\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateScheduleKeyRejected() throws Exception {
        String scheduleKey = key("SCH-DUP");
        assertThat(createPlan(scheduleKey, key("RK"), List.of(occ("G1", "SEC-DUP", 8, 9)))
                .getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePlanRequest(key("RK"), scheduleKey, DAY,
                                List.of(occ("G2", "SEC-DUP", 9, 10))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SCHEDULE_KEY"));
    }

    @Test
    void updateReplacesOccupanciesAndBumpsVersion() throws Exception {
        String scheduleKey = key("SCH-UPD");
        createPlan(scheduleKey, key("RK"), List.of(occ("G1", "SEC-UPD", 8, 9)));

        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdatePlanRequest(key("RK"), 1L,
                                List.of(occ("G2", "SEC-UPD", 10, 12), occ("G3", "SEC-UPD", 13, 14))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.occupancies.length()").value(2))
                .andExpect(jsonPath("$.occupancies[0].trainNo").value("G2"));

        mockMvc.perform(get("/api/plans/{key}", scheduleKey))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.occupancies.length()").value(2));
    }

    @Test
    void updateWithWrongVersionReturns409() throws Exception {
        String scheduleKey = key("SCH-UPDV");
        createPlan(scheduleKey, key("RK"), List.of(occ("G1", "SEC-UPDV", 8, 9)));

        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdatePlanRequest(key("RK"), 5L, List.of(occ("G2", "SEC-UPDV", 9, 10))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void updatePublishedPlanReturns409() throws Exception {
        String scheduleKey = key("SCH-UPDP");
        String section = key("SEC-UPDP");
        createPlan(scheduleKey, key("RK"), List.of(occ("G1", section, 8, 9)));
        publish(scheduleKey, key("RK"));

        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdatePlanRequest(key("RK"), 1L, List.of(occ("G2", section, 9, 10))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void publishMakesSlotsVisible() throws Exception {
        String scheduleKey = key("SCH-PUB");
        String section = key("SEC-PUB");
        createPlan(scheduleKey, key("RK"), List.of(occ("G1", section, 8, 10)));

        mockMvc.perform(post("/api/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/slots").param("date", "2026-09-21").param("sectionId", section))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scheduleKey").value(scheduleKey))
                .andExpect(jsonPath("$[0].trainNo").value("G1"));
    }

    @Test
    void publishWithInternalTrainOverlapReturns422AndStaysDraft() throws Exception {
        String scheduleKey = key("SCH-INTOVL");
        String section = key("SEC-INTOVL");
        // 同一列车在本计划内重叠
        createPlan(scheduleKey, key("RK"), List.of(
                occ("G1", section, 8, 10),
                new OccupancyInput("G1", section, sh(9, 30), sh(11, 0))));

        mockMvc.perform(post("/api/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].sectionId").value(section));

        mockMvc.perform(get("/api/plans/{key}", scheduleKey))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void publishConflictingWithPublishedPlanReturns422() throws Exception {
        String section = key("SEC-XOVL");
        String first = key("SCH-X1");
        String second = key("SCH-X2");
        createPlan(first, key("RK"), List.of(occ("G1", section, 8, 10)));
        publish(first, key("RK"));
        createPlan(second, key("RK"), List.of(occ("G2", section, 9, 11)));

        mockMvc.perform(post("/api/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].sectionId").value(section))
                .andExpect(jsonPath("$.conflicts[0].conflictingScheduleKey").value(first));

        mockMvc.perform(get("/api/plans/{key}", second))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void adjacentSlotsAreAllowed() throws Exception {
        String section = key("SEC-ADJ");
        String first = key("SCH-A1");
        String second = key("SCH-A2");
        createPlan(first, key("RK"), List.of(occ("G1", section, 8, 10)));
        publish(first, key("RK"));
        // 相邻时隙（10:00 紧接 10:00）合法
        createPlan(second, key("RK"), List.of(occ("G2", section, 10, 12)));

        mockMvc.perform(post("/api/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/slots").param("date", "2026-09-21").param("sectionId", section))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cancelReleasesSlotsAndKeepsHistory() throws Exception {
        String section = key("SEC-CXL");
        String first = key("SCH-C1");
        String second = key("SCH-C2");
        createPlan(first, key("RK"), List.of(occ("G1", section, 8, 10)));
        publish(first, key("RK"));

        mockMvc.perform(post("/api/plans/{key}/cancel", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 时隙立即释放
        mockMvc.perform(get("/api/slots").param("date", "2026-09-21").param("sectionId", section))
                .andExpect(jsonPath("$.length()").value(0));
        // 历史计划与原始占用保留
        mockMvc.perform(get("/api/plans/{key}", first))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.occupancies.length()").value(1))
                .andExpect(jsonPath("$.occupancies[0].trainNo").value("G1"));

        // 释放后原计划占用的区段可被新计划发布
        createPlan(second, key("RK"), List.of(occ("G2", section, 8, 10)));
        mockMvc.perform(post("/api/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isOk());
    }

    @Test
    void cancelDoesNotReleaseOtherPlansSlots() throws Exception {
        String section = key("SEC-CXL2");
        String first = key("SCH-D1");
        String second = key("SCH-D2");
        createPlan(first, key("RK"), List.of(occ("G1", section, 8, 9)));
        createPlan(second, key("RK"), List.of(occ("G2", section, 9, 10)));
        publish(first, key("RK"));
        publish(second, key("RK"));

        mockMvc.perform(post("/api/plans/{key}/cancel", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/slots")
                        .param("date", "2026-09-21").param("sectionId", section))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scheduleKey").value(second))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void cancelDraftOrCancelledReturns409() throws Exception {
        String draft = key("SCH-CXLD");
        createPlan(draft, key("RK"), List.of(occ("G1", key("SEC-CXLD"), 8, 9)));
        mockMvc.perform(post("/api/plans/{key}/cancel", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        String published = key("SCH-CXLP");
        createPlan(published, key("RK"), List.of(occ("G1", key("SEC-CXLP"), 8, 9)));
        publish(published, key("RK"));
        String cancelKey = key("RK");
        mockMvc.perform(post("/api/plans/{key}/cancel", published)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(cancelKey))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/plans/{key}/cancel", published)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void missingPlanReturns404() throws Exception {
        mockMvc.perform(get("/api/plans/{key}", key("SCH-NONE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/plans/{key}/publish", key("SCH-NONE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/plans/{key}/cancel", key("SCH-NONE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new KeyedRequest(key("RK")))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/plans/{key}", key("SCH-NONE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdatePlanRequest(key("RK"), 1L, List.of(occ("G1", "SEC-N", 8, 9))))))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotentCreateReplayReturnsFirstResult() throws Exception {
        String scheduleKey = key("SCH-IDEM");
        String requestKey = key("RK");
        CreatePlanRequest request = new CreatePlanRequest(requestKey, scheduleKey, DAY,
                List.of(occ("G1", "SEC-IDEM", 8, 9)));

        MvcResult first = mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键改参 -> 409
        CreatePlanRequest changed = new CreatePlanRequest(requestKey, scheduleKey, DAY,
                List.of(occ("G9", "SEC-IDEM", 9, 10)));
        mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(json(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void idempotentUpdateAndPublishReplay() throws Exception {
        String scheduleKey = key("SCH-IDEMU");
        String section = key("SEC-IDEMU");
        createPlan(scheduleKey, key("RK"), List.of(occ("G1", section, 8, 9)));

        String updateKey = key("RK");
        UpdatePlanRequest update = new UpdatePlanRequest(updateKey, 1L, List.of(occ("G2", section, 9, 10)));
        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键同参重放：返回首次结果（版本仍为 2），而不是报版本冲突
        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键改参 -> 409
        mockMvc.perform(put("/api/plans/{key}", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdatePlanRequest(updateKey, 2L, List.of(occ("G3", section, 10, 11))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        String publishKey = key("RK");
        mockMvc.perform(post("/api/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new KeyedRequest(publishKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        // 同键重放发布：返回首次结果，而不是状态冲突
        mockMvc.perform(post("/api/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new KeyedRequest(publishKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void concurrentPublishOnSameSectionAllowsOnlyOneSuccess() throws Exception {
        String section = key("SEC-RACE");
        String first = key("SCH-R1");
        String second = key("SCH-R2");
        createPlan(first, key("RK"), List.of(occ("G1", section, 8, 10)));
        createPlan(second, key("RK"), List.of(occ("G2", section, 9, 11)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<PlanResponse> taskA = () -> {
            ready.countDown();
            go.await();
            return planService.publish(first, new KeyedRequest(key("RK")));
        };
        Callable<PlanResponse> taskB = () -> {
            ready.countDown();
            go.await();
            return planService.publish(second, new KeyedRequest(key("RK")));
        };
        Future<PlanResponse> futureA = pool.submit(taskA);
        Future<PlanResponse> futureB = pool.submit(taskB);
        assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        go.countDown();

        int succeeded = 0;
        int conflicted = 0;
        for (Future<PlanResponse> future : List.of(futureA, futureB)) {
            try {
                future.get();
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                ApiException apiException = (ApiException) e.getCause();
                assertThat(apiException.status().value()).isEqualTo(422);
                conflicted++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);

        mockMvc.perform(get("/api/slots").param("date", "2026-09-21").param("sectionId", section))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
