package com.example.starter.site;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 现场隔离与作业许可 API 的端到端单元测试（H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SiteApiTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM command_record");
        jdbc.update("DELETE FROM permit_approval");
        jdbc.update("DELETE FROM permit_isolation");
        jdbc.update("DELETE FROM permit");
        jdbc.update("DELETE FROM isolation_record");
    }

    // ---------- 请求辅助 ----------

    private MvcResult createIsolation(String commandKey, String key, String device,
                                      long startHour, long endHour) throws Exception {
        Map<String, Object> body = Map.of(
                "commandKey", commandKey,
                "isolationKey", key,
                "deviceId", device,
                "plannedStartUtc", T0.plus(startHour, ChronoUnit.HOURS).toString(),
                "plannedEndUtc", T0.plus(endHour, ChronoUnit.HOURS).toString(),
                "lockedBy", "locker-1");
        return mvc.perform(post("/api/isolations")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult removeIsolation(String key, String commandKey) throws Exception {
        return mvc.perform(post("/api/isolations/{key}/remove", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("commandKey", commandKey))))
                .andReturn();
    }

    private MvcResult createPermit(String commandKey, String key, long startHour, long endHour,
                                   String applicant, List<String> isolationKeys) throws Exception {
        Map<String, Object> body = Map.of(
                "commandKey", commandKey,
                "permitKey", key,
                "crewName", "crew-A",
                "workStartUtc", T0.plus(startHour, ChronoUnit.HOURS).toString(),
                "workEndUtc", T0.plus(endHour, ChronoUnit.HOURS).toString(),
                "applicant", applicant,
                "isolationKeys", isolationKeys);
        return mvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult approve(String permitKey, String actor, String commandKey) throws Exception {
        return mvc.perform(post("/api/permits/{key}/approvals", permitKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("commandKey", commandKey))))
                .andReturn();
    }

    private MvcResult closePermit(String permitKey, String actor, String commandKey) throws Exception {
        return mvc.perform(post("/api/permits/{key}/close", permitKey)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("commandKey", commandKey))))
                .andReturn();
    }

    private void installed(String key, String device, long startHour, long endHour) throws Exception {
        assertEquals(201, createIsolation("ck-iso-" + key, key, device, startHour, endHour)
                .getResponse().getStatus());
    }

    private void makeEffective(String permitKey) throws Exception {
        assertEquals(200, approve(permitKey, "bob", "ck-ap1-" + permitKey).getResponse().getStatus());
        assertEquals(200, approve(permitKey, "carol", "ck-ap2-" + permitKey).getResponse().getStatus());
    }

    private List<MvcResult> runConcurrent(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                return task.call();
            }));
        }
        gate.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    // ---------- 隔离创建 ----------

    @Test
    void createIsolationSuccessAndQuery() throws Exception {
        MvcResult created = createIsolation("ck-c1", "ISO-1", "DEV-1", 0, 10);
        assertEquals(201, created.getResponse().getStatus());

        mvc.perform(get("/api/isolations/ISO-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isolationKey").value("ISO-1"))
                .andExpect(jsonPath("$.deviceId").value("DEV-1"))
                .andExpect(jsonPath("$.status").value("INSTALLED"))
                .andExpect(jsonPath("$.lockedBy").value("locker-1"))
                .andExpect(jsonPath("$.removedAt").doesNotExist());

        mvc.perform(get("/api/isolations").param("deviceId", "DEV-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createIsolationInvalidParamsReturn400() throws Exception {
        // 开始不早于结束
        assertEquals(400, createIsolation("ck-b1", "ISO-B1", "DEV-1", 10, 10).getResponse().getStatus());
        assertEquals(400, createIsolation("ck-b2", "ISO-B2", "DEV-1", 12, 10).getResponse().getStatus());
        // 缺少必填字段
        MvcResult missing = mvc.perform(post("/api/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("isolationKey", "ISO-B3"))))
                .andReturn();
        assertEquals(400, missing.getResponse().getStatus());
    }

    @Test
    void createIsolationDuplicateKeyReturns409() throws Exception {
        installed("ISO-D1", "DEV-1", 0, 10);
        MvcResult duplicate = createIsolation("ck-dup", "ISO-D1", "DEV-2", 20, 30);
        assertEquals(409, duplicate.getResponse().getStatus());
        assertTrue(duplicate.getResponse().getContentAsString().contains("ISOLATION_KEY_EXISTS"));
    }

    @Test
    void overlappingIsolationRejectedButAdjacentAllowed() throws Exception {
        installed("ISO-O1", "DEV-1", 0, 10);
        // 重叠 → 409
        assertEquals(409, createIsolation("ck-o2", "ISO-O2", "DEV-1", 5, 15).getResponse().getStatus());
        assertEquals(409, createIsolation("ck-o3", "ISO-O3", "DEV-1", 0, 10).getResponse().getStatus());
        assertEquals(409, createIsolation("ck-o4", "ISO-O4", "DEV-1", 2, 8).getResponse().getStatus());
        // 端点相邻合法
        assertEquals(201, createIsolation("ck-o5", "ISO-O5", "DEV-1", 10, 20).getResponse().getStatus());
        // 不同设备互不影响
        assertEquals(201, createIsolation("ck-o6", "ISO-O6", "DEV-2", 0, 10).getResponse().getStatus());
        // 拆除后可复用区间
        assertEquals(200, removeIsolation("ISO-O1", "ck-rm-o1").getResponse().getStatus());
        assertEquals(201, createIsolation("ck-o7", "ISO-O7", "DEV-1", 0, 10).getResponse().getStatus());
    }

    @Test
    void commandKeyReplayReturnsOriginalAndChangedParamsReturn409() throws Exception {
        MvcResult first = createIsolation("ck-idem", "ISO-I1", "DEV-1", 0, 10);
        assertEquals(201, first.getResponse().getStatus());
        // 同键同参重放 → 原结果（含原始响应体）
        MvcResult replay = createIsolation("ck-idem", "ISO-I1", "DEV-1", 0, 10);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 同键不同参 → 409
        MvcResult changed = createIsolation("ck-idem", "ISO-I2", "DEV-1", 0, 10);
        assertEquals(409, changed.getResponse().getStatus());
        assertTrue(changed.getResponse().getContentAsString().contains("COMMAND_KEY_CONFLICT"));
    }

    // ---------- 隔离拆除 ----------

    @Test
    void removeIsolationLifecycle() throws Exception {
        installed("ISO-R1", "DEV-1", 0, 10);
        MvcResult removed = removeIsolation("ISO-R1", "ck-rm1");
        assertEquals(200, removed.getResponse().getStatus());
        assertTrue(removed.getResponse().getContentAsString().contains("REMOVED"));
        // 重复拆除 → 409
        assertEquals(409, removeIsolation("ISO-R1", "ck-rm2").getResponse().getStatus());
        // 不存在 → 404
        assertEquals(404, removeIsolation("ISO-NOPE", "ck-rm3").getResponse().getStatus());
        // 拆除后明细仍可查询
        mvc.perform(get("/api/isolations/ISO-R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.removedAt").exists());
    }

    // ---------- 许可创建 ----------

    @Test
    void createPermitSuccessWithCombinedCoverage() throws Exception {
        installed("ISO-P1", "DEV-1", 0, 5);
        installed("ISO-P2", "DEV-2", 5, 10);
        MvcResult created = createPermit("ck-p1", "PM-1", 0, 10, "alice", List.of("ISO-P1", "ISO-P2"));
        assertEquals(201, created.getResponse().getStatus());

        mvc.perform(get("/api/permits/PM-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.applicant").value("alice"))
                .andExpect(jsonPath("$.isolationKeys.length()").value(2))
                .andExpect(jsonPath("$.approvals.length()").value(0));
    }

    @Test
    void createPermitPreconditionFailuresReturn422() throws Exception {
        installed("ISO-Q1", "DEV-1", 0, 5);
        // 引用不存在的隔离
        MvcResult missing = createPermit("ck-q1", "PM-Q1", 0, 5, "alice", List.of("ISO-NOPE"));
        assertEquals(422, missing.getResponse().getStatus());
        // 引用已拆除的隔离
        installed("ISO-Q2", "DEV-2", 0, 5);
        assertEquals(200, removeIsolation("ISO-Q2", "ck-q-rm").getResponse().getStatus());
        assertEquals(422, createPermit("ck-q2", "PM-Q2", 0, 5, "alice", List.of("ISO-Q2"))
                .getResponse().getStatus());
        // 覆盖不完整（中间有空隙）
        installed("ISO-Q3", "DEV-3", 0, 4);
        installed("ISO-Q4", "DEV-4", 6, 10);
        MvcResult gap = createPermit("ck-q3", "PM-Q3", 0, 10, "alice", List.of("ISO-Q3", "ISO-Q4"));
        assertEquals(422, gap.getResponse().getStatus());
        assertTrue(gap.getResponse().getContentAsString().contains("WORK_INTERVAL_NOT_COVERED"));
        // 覆盖不完整（边界不足）
        assertEquals(422, createPermit("ck-q4", "PM-Q4", 0, 10, "alice", List.of("ISO-Q1"))
                .getResponse().getStatus());
    }

    @Test
    void createPermitInvalidParamsReturn400() throws Exception {
        installed("ISO-V1", "DEV-1", 0, 10);
        // 作业区间非法
        assertEquals(400, createPermit("ck-v1", "PM-V1", 10, 10, "alice", List.of("ISO-V1"))
                .getResponse().getStatus());
        // 隔离数量为 0
        assertEquals(400, createPermit("ck-v2", "PM-V2", 0, 10, "alice", List.of())
                .getResponse().getStatus());
        // 隔离数量超过 20
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("ISO-V1");
        }
        assertEquals(400, createPermit("ck-v3", "PM-V3", 0, 10, "alice", tooMany)
                .getResponse().getStatus());
    }

    @Test
    void createPermitDuplicateKeyReturns409() throws Exception {
        installed("ISO-DP1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-dp1", "PM-D1", 0, 10, "alice", List.of("ISO-DP1"))
                .getResponse().getStatus());
        assertEquals(409, createPermit("ck-dp2", "PM-D1", 0, 10, "alice", List.of("ISO-DP1"))
                .getResponse().getStatus());
    }

    // ---------- 批准 ----------

    @Test
    void approveTwiceByDistinctActorsMakesPermitEffective() throws Exception {
        installed("ISO-A1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-a1", "PM-A1", 0, 10, "alice", List.of("ISO-A1"))
                .getResponse().getStatus());

        MvcResult first = approve("PM-A1", "bob", "ck-a-ap1");
        assertEquals(200, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsString().contains("PENDING"));

        MvcResult second = approve("PM-A1", "carol", "ck-a-ap2");
        assertEquals(200, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("EFFECTIVE"));

        mvc.perform(get("/api/permits/PM-A1"))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.approvals.length()").value(2))
                .andExpect(jsonPath("$.approvals[0].approver").value("bob"))
                .andExpect(jsonPath("$.approvals[1].approver").value("carol"));

        mvc.perform(get("/api/permits/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].permitKey").value("PM-A1"));
    }

    @Test
    void approveFailureBranches() throws Exception {
        installed("ISO-F1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-f1", "PM-F1", 0, 10, "alice", List.of("ISO-F1"))
                .getResponse().getStatus());
        // 缺少 X-Actor-Id → 400
        MvcResult noActor = mvc.perform(post("/api/permits/PM-F1/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("commandKey", "ck-f-noactor"))))
                .andReturn();
        assertEquals(400, noActor.getResponse().getStatus());
        // 审核人是申请人 → 422
        assertEquals(422, approve("PM-F1", "alice", "ck-f-applicant").getResponse().getStatus());
        // 许可不存在 → 404
        assertEquals(404, approve("PM-NOPE", "bob", "ck-f-404").getResponse().getStatus());
        // 同一审核人重复批准 → 409
        assertEquals(200, approve("PM-F1", "bob", "ck-f-bob").getResponse().getStatus());
        assertEquals(409, approve("PM-F1", "bob", "ck-f-bob2").getResponse().getStatus());
        // 生效后再批准 → 409
        assertEquals(200, approve("PM-F1", "carol", "ck-f-carol").getResponse().getStatus());
        assertEquals(409, approve("PM-F1", "dave", "ck-f-dave").getResponse().getStatus());
    }

    @Test
    void approveAfterIsolationRemovedRejectedWithoutPartialApproval() throws Exception {
        installed("ISO-X1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-x1", "PM-X1", 0, 10, "alice", List.of("ISO-X1"))
                .getResponse().getStatus());
        // 无生效许可引用，允许拆除
        assertEquals(200, removeIsolation("ISO-X1", "ck-x-rm").getResponse().getStatus());
        // 批准时发现隔离已拆除 → 422，且不产生部分批准
        MvcResult rejected = approve("PM-X1", "bob", "ck-x-ap1");
        assertEquals(422, rejected.getResponse().getStatus());
        mvc.perform(get("/api/permits/PM-X1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvals.length()").value(0));
        // 失败结果按 commandKey 幂等重放
        assertEquals(422, approve("PM-X1", "bob", "ck-x-ap1").getResponse().getStatus());
    }

    @Test
    void approveRejectedWhenIsolationOccupiedByOverlappingEffectivePermit() throws Exception {
        installed("ISO-OCC", "DEV-1", 0, 20);
        assertEquals(201, createPermit("ck-occ-a", "PM-OCC-A", 0, 10, "alice", List.of("ISO-OCC"))
                .getResponse().getStatus());
        assertEquals(201, createPermit("ck-occ-b", "PM-OCC-B", 5, 15, "alice", List.of("ISO-OCC"))
                .getResponse().getStatus());
        // B 先完成首审（此时 A 未生效，无占用冲突）
        assertEquals(200, approve("PM-OCC-B", "bob", "ck-occ-b1").getResponse().getStatus());
        // A 生效
        makeEffective("PM-OCC-A");
        // B 次审时发现隔离被 A 在重叠时段占用 → 422，首审保留、许可仍待审
        MvcResult rejected = approve("PM-OCC-B", "carol", "ck-occ-b2");
        assertEquals(422, rejected.getResponse().getStatus());
        assertTrue(rejected.getResponse().getContentAsString().contains("ISOLATION_OCCUPIED"));
        mvc.perform(get("/api/permits/PM-OCC-B"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvals.length()").value(1));
        // 时段不重叠的许可可以生效（端点相邻不算重叠）
        assertEquals(201, createPermit("ck-occ-c", "PM-OCC-C", 10, 20, "alice", List.of("ISO-OCC"))
                .getResponse().getStatus());
        makeEffective("PM-OCC-C");
        mvc.perform(get("/api/permits/PM-OCC-C"))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));
    }

    // ---------- 关闭与拆除联动 ----------

    @Test
    void closePermitLifecycle() throws Exception {
        installed("ISO-C1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-c1", "PM-C1", 0, 10, "alice", List.of("ISO-C1"))
                .getResponse().getStatus());
        // 待审批许可不能关闭 → 409
        assertEquals(409, closePermit("PM-C1", "alice", "ck-c-close0").getResponse().getStatus());
        makeEffective("PM-C1");
        // 非申请人不能关闭 → 422
        assertEquals(422, closePermit("PM-C1", "bob", "ck-c-close1").getResponse().getStatus());
        // 申请人关闭 → 200
        MvcResult closed = closePermit("PM-C1", "alice", "ck-c-close2");
        assertEquals(200, closed.getResponse().getStatus());
        assertTrue(closed.getResponse().getContentAsString().contains("CLOSED"));
        // 重复关闭 → 409
        assertEquals(409, closePermit("PM-C1", "alice", "ck-c-close3").getResponse().getStatus());
        // 许可不存在 → 404
        assertEquals(404, closePermit("PM-NOPE", "alice", "ck-c-close4").getResponse().getStatus());
        // 生效列表不再包含已关闭许可
        mvc.perform(get("/api/permits/effective"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void isolationRemovableOnlyAfterPermitClosed() throws Exception {
        installed("ISO-L1", "DEV-1", 0, 10);
        assertEquals(201, createPermit("ck-l1", "PM-L1", 0, 10, "alice", List.of("ISO-L1"))
                .getResponse().getStatus());
        makeEffective("PM-L1");
        // 生效许可引用中 → 409
        assertEquals(409, removeIsolation("ISO-L1", "ck-l-rm1").getResponse().getStatus());
        // 关闭后允许拆除
        assertEquals(200, closePermit("PM-L1", "alice", "ck-l-close").getResponse().getStatus());
        assertEquals(200, removeIsolation("ISO-L1", "ck-l-rm2").getResponse().getStatus());
    }

    // ---------- 历史明细查询 ----------

    @Test
    void historyQueries() throws Exception {
        installed("ISO-H1", "DEV-1", 0, 10);
        installed("ISO-H2", "DEV-2", 0, 10);
        assertEquals(201, createPermit("ck-h1", "PM-H1", 0, 10, "alice", List.of("ISO-H1"))
                .getResponse().getStatus());
        makeEffective("PM-H1");
        assertEquals(201, createPermit("ck-h2", "PM-H2", 0, 10, "alice", List.of("ISO-H2"))
                .getResponse().getStatus());

        mvc.perform(get("/api/permits"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/permits").param("status", "EFFECTIVE"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].permitKey").value("PM-H1"));
        mvc.perform(get("/api/permits").param("status", "PENDING"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].permitKey").value("PM-H2"));
        mvc.perform(get("/api/permits").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/isolations").param("status", "INSTALLED"))
                .andExpect(jsonPath("$.length()").value(2));
        // 许可明细含批准历史
        mvc.perform(get("/api/permits/PM-H1"))
                .andExpect(jsonPath("$.approvals.length()").value(2))
                .andExpect(jsonPath("$.isolationKeys[0]").value("ISO-H1"));
    }

    // ---------- 并发与幂等边界 ----------

    @Test
    void concurrentOverlappingIsolationCreationAtMostOneSucceeds() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int idx = i;
            tasks.add(() -> createIsolation("ck-cc-" + idx, "ISO-CC-" + idx, "DEV-CC", 0, 10));
        }
        List<MvcResult> results = runConcurrent(tasks);
        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long conflicts = results.stream().filter(r -> r.getResponse().getStatus() == 409).count();
        assertEquals(1, created);
        assertEquals(7, conflicts);
        // 数据库中确实只有一条已安装隔离
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_record WHERE device_id = 'DEV-CC' AND status = 'INSTALLED'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void concurrentApprovalsByTwoActorsMakePermitEffectiveExactlyOnce() throws Exception {
        installed("ISO-CA", "DEV-CA", 0, 10);
        assertEquals(201, createPermit("ck-ca", "PM-CA", 0, 10, "alice", List.of("ISO-CA"))
                .getResponse().getStatus());
        List<MvcResult> results = runConcurrent(List.of(
                () -> approve("PM-CA", "bob", "ck-ca-bob"),
                () -> approve("PM-CA", "carol", "ck-ca-carol")));
        assertEquals(2, results.stream().filter(r -> r.getResponse().getStatus() == 200).count());
        mvc.perform(get("/api/permits/PM-CA"))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.approvals.length()").value(2));
    }

    @Test
    void concurrentApprovalsBySameActorOnlyOneSucceeds() throws Exception {
        installed("ISO-SA", "DEV-SA", 0, 10);
        assertEquals(201, createPermit("ck-sa", "PM-SA", 0, 10, "alice", List.of("ISO-SA"))
                .getResponse().getStatus());
        List<MvcResult> results = runConcurrent(List.of(
                () -> approve("PM-SA", "bob", "ck-sa-1"),
                () -> approve("PM-SA", "bob", "ck-sa-2")));
        assertEquals(1, results.stream().filter(r -> r.getResponse().getStatus() == 200).count());
        assertEquals(1, results.stream().filter(r -> r.getResponse().getStatus() == 409).count());
        mvc.perform(get("/api/permits/PM-SA"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvals.length()").value(1));
    }

    @Test
    void concurrentSameCommandKeyReplayReturnsSameResult() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> createIsolation("ck-same", "ISO-SAME", "DEV-SAME", 0, 10));
        }
        List<MvcResult> results = runConcurrent(tasks);
        String firstBody = results.get(0).getResponse().getContentAsString();
        for (MvcResult result : results) {
            assertEquals(201, result.getResponse().getStatus());
            assertEquals(firstBody, result.getResponse().getContentAsString());
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_record WHERE isolation_key = 'ISO-SAME'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void concurrentRemoveAndApproveKeepsConsistentOutcome() throws Exception {
        installed("ISO-RC", "DEV-RC", 0, 10);
        assertEquals(201, createPermit("ck-rc", "PM-RC", 0, 10, "alice", List.of("ISO-RC"))
                .getResponse().getStatus());
        // 拆除与批准并发：按提交顺序生效，结果必须自洽
        List<MvcResult> results = runConcurrent(List.of(
                () -> removeIsolation("ISO-RC", "ck-rc-rm"),
                () -> approve("PM-RC", "bob", "ck-rc-ap1"),
                () -> approve("PM-RC", "carol", "ck-rc-ap2")));
        boolean removed = results.get(0).getResponse().getStatus() == 200;
        MvcResult permit = mvc.perform(get("/api/permits/PM-RC")).andReturn();
        String body = permit.getResponse().getContentAsString();
        if (removed) {
            // 拆除先提交时许可绝不可能生效
            assertTrue(body.contains("PENDING"));
        } else {
            // 拆除失败（许可先生效）时许可必须为生效且隔离仍安装
            assertTrue(body.contains("EFFECTIVE"));
            mvc.perform(get("/api/isolations/ISO-RC"))
                    .andExpect(jsonPath("$.status").value("INSTALLED"));
        }
    }
}
