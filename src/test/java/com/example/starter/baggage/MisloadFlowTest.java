package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.baggage.MisloadDtos.ConfirmRerouteRequest;
import com.example.starter.baggage.MisloadDtos.MisloadBagItem;
import com.example.starter.baggage.MisloadDtos.PreviewRerouteRequest;
import com.example.starter.baggage.MisloadDtos.RegisterMisloadRequest;
import com.example.starter.baggage.MisloadDtos.RerouteBagRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错装行李批次追回与剩余路径原子改派测试：覆盖登记/预览/确认主流程、失败分支整单回滚、
 * 原剩余路径禁装与新路径装载、不可变路径血缘、幂等边界以及基于真实 H2 数据库的并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MisloadFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private MisloadService misloadService;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM path_snapshot");
        jdbcTemplate.update("DELETE FROM misload_reroute_plan");
        jdbcTemplate.update("DELETE FROM misload_item");
        jdbcTemplate.update("DELETE FROM misload_incident");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void misloadMainFlow_registerPreviewConfirmAndRerouteToDestination() throws Exception {
        setupStandardScenario();

        // 登记错装批次：BA/BB 在 SHA 被 X1（HGH->SHA）错运到达，X1 不在任何一件行李行程中
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.arrivalStation").value("SHA"))
                .andExpect(jsonPath("$.bagTags", contains("BA", "BB")));

        // 未结错装期间：不得装载原剩余航段、不得补到
        load("L2", 1, List.of("BA")).andExpect(status().isUnprocessableEntity());
        recover("BA", "L2", "CAN").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BA/trace"))
                .andExpect(jsonPath("$.status").value("MISLOADED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.pathGeneration").value(1))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "MISLOADED")));

        // 预览冻结：BA 原剩余 [L2] -> 恢复 [R1,R2]；BB 原剩余 [L2,L3] -> 恢复 [R1,R2,R3]
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bags", hasSize(2)))
                .andExpect(jsonPath("$.bags[0].bagTag").value("BA"))
                .andExpect(jsonPath("$.bags[0].bagVersion").value(4))
                .andExpect(jsonPath("$.bags[0].originalRemaining", contains("L2")))
                .andExpect(jsonPath("$.bags[0].recoveryPath", contains("R1", "R2")))
                .andExpect(jsonPath("$.bags[1].originalRemaining", contains("L2", "L3")))
                .andExpect(jsonPath("$.bags[1].recoveryPath", contains("R1", "R2", "R3")));

        // 确认原子改派
        confirm("INC1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.bags", hasSize(2)))
                .andExpect(jsonPath("$.bags[0].bagTag").value("BA"))
                .andExpect(jsonPath("$.bags[0].newVersion").value(5))
                .andExpect(jsonPath("$.bags[0].pathGeneration").value(2))
                .andExpect(jsonPath("$.bags[0].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.bags[0].newRemainingPath", contains("R1", "R2")));

        mockMvc.perform(get("/api/bags/BA/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.pathGeneration").value(2))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "R1", "R2")))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "MISLOADED", "REROUTED")));
        mockMvc.perform(get("/api/bags/BB/trace"))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "R1", "R2", "R3")));

        // 原剩余航段不得再装载：BA 待乘首段已是 R1
        load("L2", 1, List.of("BA")).andExpect(status().isUnprocessableEntity());

        // 新路径可正常装载并走完：BA 经 R1/R2 到 CAN 交付
        load("R1", 1, List.of("BA", "BB")).andExpect(status().isOk());
        seal("R1", 2);
        arriveExact("R1", List.of("BA", "BB")).andExpect(status().isOk());
        load("R2", 1, List.of("BA", "BB")).andExpect(status().isOk());
        seal("R2", 2);
        arriveExact("R2", List.of("BA", "BB")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BA/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // BB 继续 R3 到 SZX 交付
        load("R3", 1, List.of("BB")).andExpect(status().isOk());
        seal("R3", 2);
        arriveExact("R3", List.of("BB")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BB/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SZX"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void lineage_returnsImmutableOriginalAndNewSnapshots() throws Exception {
        setupStandardScenario();
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3")))).andExpect(status().isOk());
        confirm("INC1").andExpect(status().isOk());

        mockMvc.perform(get("/api/misloads/bags/BA/lineage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathGeneration").value(2))
                .andExpect(jsonPath("$.snapshots", hasSize(3)))
                .andExpect(jsonPath("$.snapshots[0].pathKind").value("ORIGINAL"))
                .andExpect(jsonPath("$.snapshots[0].generation").value(1))
                .andExpect(jsonPath("$.snapshots[0].seq").value(0))
                .andExpect(jsonPath("$.snapshots[0].legId").value("L2"))
                .andExpect(jsonPath("$.snapshots[1].pathKind").value("NEW"))
                .andExpect(jsonPath("$.snapshots[1].generation").value(2))
                .andExpect(jsonPath("$.snapshots[1].legId").value("R1"))
                .andExpect(jsonPath("$.snapshots[2].pathKind").value("NEW"))
                .andExpect(jsonPath("$.snapshots[2].legId").value("R2"));
        mockMvc.perform(get("/api/misloads/bags/BB/lineage"))
                .andExpect(jsonPath("$.snapshots", hasSize(5)))
                .andExpect(jsonPath("$.snapshots[?(@.pathKind=='ORIGINAL' && @.legId=='L3')]",
                        hasSize(1)));

        // 血缘为不可变快照：再次确认被拒，快照行数不随后续装载增长
        confirm("INC1").andExpect(status().isConflict());
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_snapshot WHERE bag_tag = 'BA'", Integer.class);
        assertThat(snapshots).isEqualTo(3);
    }

    @Test
    void register_rejectsDeliveredInTransitOpenIncidentStationAndItineraryMismatch() throws Exception {
        setupStandardScenario();

        // 已交付行李：BD2 只有 D1，走完已交付
        registerLeg("D1", "PEK", "SHA", null);
        registerBag("BD2", List.of("D1"));
        load("D1", 1, List.of("BD2"));
        seal("D1", 2);
        arriveExact("D1", List.of("BD2")).andExpect(status().isOk());

        // 已在途：BE 已装载未到达
        registerLeg("E1", "PEK", "SHA", null);
        registerBag("BE", List.of("E1"));
        load("E1", 1, List.of("BE")).andExpect(status().isOk());

        // 站点候选：BC 位于 PEK 未起飞
        registerBag("BC", List.of("F1", "L2"));

        // 已交付 -> 422，整单无改态
        registerMisload("INC_D", "X1", List.of(misloadBag("BD2", 3), misloadBag("BA", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 已在途 -> 422
        registerMisload("INC_E", "X1", List.of(misloadBag("BE", 2), misloadBag("BA", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 扫描站与实际航段到达站不符 -> 422
        registerMisload("INC_S", "X1",
                List.of(new MisloadBagItem("BA", 3, "CAN"), misloadBag("BB", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 行李当前站与扫描站不符（BC 尚在 PEK）-> 422
        registerMisload("INC_L", "X1", List.of(misloadBag("BC", 1), misloadBag("BA", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 实际航段属于其中一件行李行程 -> 422（L1 以 SHA 为到达站且在 BA/BB 行程中）
        registerMisload("INC_I", "L1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 行李不存在 / 实际航段不存在 -> 422
        registerMisload("INC_U", "X1", List.of(misloadBag("NOPE", 1), misloadBag("BA", 3)))
                .andExpect(status().isUnprocessableEntity());
        registerMisload("INC_U2", "NOPE", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isUnprocessableEntity());

        assertNoMisloadAndBagsUntouched();

        // 成功登记后，同件行李再登记第二个未结事件 -> 422
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());
        registerMisload("INC2", "X1", List.of(misloadBag("BA", 4), misloadBag("BC", 1)))
                .andExpect(status().isUnprocessableEntity());
        Integer incidentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class);
        assertThat(incidentCount).isEqualTo(1);
    }

    @Test
    void register_wholeOrderRollsBackWhenOneBagInvalid() throws Exception {
        setupStandardScenario();
        // BB 版本号填错：整单 409，BA 不得改态
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 99)))
                .andExpect(status().isConflict());
        assertNoMisloadAndBagsUntouched();

        // 一件扫描站错误：整单 422，无行李改态
        registerMisload("INC1", "X1",
                List.of(misloadBag("BA", 3), new MisloadBagItem("BB", 3, "PEK")))
                .andExpect(status().isUnprocessableEntity());
        assertNoMisloadAndBagsUntouched();
        mockMvc.perform(get("/api/bags/BA/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void register_rejectsVersionMismatchDuplicateBagSizeAndDuplicateKey() throws Exception {
        setupStandardScenario();
        // 版本不符 -> 409
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 99), misloadBag("BB", 3)))
                .andExpect(status().isConflict());
        // 批次内 bagTag 重复 -> 422
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BA", 3)))
                .andExpect(status().isUnprocessableEntity());
        // 件数越界 -> 400
        postJson("/api/misloads", Map.of("requestId", UUID.randomUUID().toString(),
                "incidentKey", "INC1", "actualLegId", "X1",
                "bags", List.of(misloadBag("BA", 3))))
                .andExpect(status().isBadRequest());

        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());
        // incidentKey 唯一：重复登记 -> 409
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 4), misloadBag("BB", 4)))
                .andExpect(status().isConflict());
    }

    @Test
    void preview_validatesPathsAndFreezes() throws Exception {
        setupStandardScenario();
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());

        // 行李集合与登记不一致：少一件 / 多一件 -> 422
        preview("INC1", List.of(path("BA", List.of("R1", "R2"))))
                .andExpect(status().isUnprocessableEntity());
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3")), path("BC", List.of("R1"))))
                .andExpect(status().isUnprocessableEntity());
        // 预览内 bagTag 重复 -> 422
        preview("INC1", List.of(path("BA", List.of("R1", "R2")), path("BA", List.of("R1", "R2"))))
                .andExpect(status().isUnprocessableEntity());
        // 首段起点不匹配当前站（R2 起点 WUH）-> 422
        preview("INC1", List.of(path("BA", List.of("R2")), path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        // 末段终点不等于原最终目的地（BA 只给 R1 终点 WUH）-> 422
        preview("INC1", List.of(path("BA", List.of("R1")), path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        // 段间站点不连续（R1 到 WUH，R3 从 CAN 起飞）-> 422
        preview("INC1", List.of(path("BA", List.of("R1", "R3")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        // 出发时间未登记无法校验严格递增（S1 无出发时间）-> 422
        preview("INC1", List.of(path("BA", List.of("S1", "S2")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        // 出发时间非严格递增（T1 10:00 晚于 T2 09:00）-> 422
        preview("INC1", List.of(path("BA", List.of("T1", "T2")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        // 路径航段重复 -> 422；引用不存在航段 -> 422；段数超 5 -> 400
        preview("INC1", List.of(path("BA", List.of("R1", "R1")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        preview("INC1", List.of(path("BA", List.of("MISSING")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isUnprocessableEntity());
        previewRaw(UUID.randomUUID().toString(), "INC1", List.of(Map.of("bagTag", "BA",
                        "path", List.of("R1", "R2", "R3", "R1", "R2", "R3")),
                Map.of("bagTag", "BB", "path", List.of("R1", "R2", "R3"))))
                .andExpect(status().isBadRequest());

        // 全部失败均不落冻结
        Integer planCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_reroute_plan", Integer.class);
        assertThat(planCount).isZero();

        // 合法预览冻结；再次预览整组替换旧冻结
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3")))).andExpect(status().isOk());
        preview("INC1", List.of(path("BB", List.of("R1", "R2", "R3")),
                path("BA", List.of("R1", "R2")))).andExpect(status().isOk());
        Integer plans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_reroute_plan", Integer.class);
        assertThat(plans).isEqualTo(2);

        // 未知事件 -> 404；已关闭事件不能再预览
        confirm("INC1").andExpect(status().isOk());
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3"))))
                .andExpect(status().isConflict());
        preview("NOPE", List.of(path("BA", List.of("R1", "R2"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirm_rejectsWhenAnyRecoveryLegSealedWithFullRollback() throws Exception {
        setupStandardScenario();
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());
        // 恢复段 R1 先封舱（空舱也允许），预览仍可冻结
        seal("R1", 1).andExpect(status().isOk());
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3")))).andExpect(status().isOk());

        // 确认：任一恢复段已封舱 -> 422，整单回滚
        confirm("INC1").andExpect(status().isUnprocessableEntity());

        String incidentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM misload_incident WHERE incident_key = 'INC1'", String.class);
        assertThat(incidentStatus).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_snapshot", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT path_generation FROM bag WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/api/bags/BA/trace"))
                .andExpect(jsonPath("$.status").value("MISLOADED"))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2")));
        mockMvc.perform(get("/api/bags/BB/trace"))
                .andExpect(jsonPath("$.itinerary[*].legId", contains("L1", "L2", "L3")));
    }

    @Test
    void confirm_rejectsWithoutPreviewAndWhenBagVersionChanged() throws Exception {
        setupStandardScenario();
        registerBag("BC", List.of("F1", "L2", "L3"));
        flyBagTo("BC", "F1");
        registerBag("BD", List.of("F2", "L2"));
        flyBagTo("BD", "F2");
        registerMisload("INC9", "X1", List.of(misloadBag("BC", 3), misloadBag("BD", 3)))
                .andExpect(status().isCreated());
        // 未预览 -> 422
        confirm("INC9").andExpect(status().isUnprocessableEntity());
        preview("INC9", List.of(path("BC", List.of("R1", "R2", "R3")),
                path("BD", List.of("R1", "R2")))).andExpect(status().isOk());

        // 预览后某件版本被其他提交推进：整单 409 且不改任何一件
        jdbcTemplate.update("UPDATE bag SET version = 99 WHERE bag_tag = 'BC'");
        confirm("INC9").andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_snapshot", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM misload_incident WHERE incident_key = 'INC9'", String.class))
                .isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BD'", String.class)).isEqualTo("MISLOADED");
        confirm("NOPE").andExpect(status().isNotFound());
    }

    @Test
    void idempotency_reorderReplaysDifferentParamsConflictAndFailureNotOccupying() throws Exception {
        setupStandardScenario();

        // 登记：行李集合换序同参重放
        String registerKey = UUID.randomUUID().toString();
        registerMisloadRaw(registerKey, "INC1", "X1",
                List.of(misloadBag("BA", 3), misloadBag("BB", 3))).andExpect(status().isCreated());
        registerMisloadRaw(registerKey, "INC1", "X1",
                List.of(misloadBag("BB", 3), misloadBag("BA", 3))).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_item", Integer.class)).isEqualTo(2);
        // 同键异参（BA 版本不同）-> 409
        registerMisloadRaw(registerKey, "INC1", "X1",
                List.of(misloadBag("BA", 4), misloadBag("BB", 3)))
                .andExpect(status().isConflict());

        // 预览：逐件行李换序同参重放；路径顺序有意义
        String previewKey = UUID.randomUUID().toString();
        previewRaw(previewKey, "INC1", List.of(
                Map.of("bagTag", "BA", "path", List.of("R1", "R2")),
                Map.of("bagTag", "BB", "path", List.of("R1", "R2", "R3"))))
                .andExpect(status().isOk());
        previewRaw(previewKey, "INC1", List.of(
                Map.of("bagTag", "BB", "path", List.of("R1", "R2", "R3")),
                Map.of("bagTag", "BA", "path", List.of("R1", "R2"))))
                .andExpect(status().isOk());
        // 路径换序 = 异参 -> 409
        previewRaw(previewKey, "INC1", List.of(
                Map.of("bagTag", "BA", "path", List.of("R2", "R1")),
                Map.of("bagTag", "BB", "path", List.of("R1", "R2", "R3"))))
                .andExpect(status().isConflict());

        // 确认同参重放：事件只关闭一次、代次只推进一次
        String confirmKey = UUID.randomUUID().toString();
        confirmRaw(confirmKey, "INC1").andExpect(status().isOk());
        confirmRaw(confirmKey, "INC1").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT path_generation FROM bag WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_snapshot", Integer.class)).isEqualTo(8);

        // 失败不占键：新事件 INC2 先用错误版本失败，再用同键正确参数成功
        registerBag("BD", List.of("F1", "L2"));
        flyBagTo("BD", "F1");
        registerBag("BE", List.of("F2", "L2", "L3"));
        flyBagTo("BE", "F2");
        String retryKey = UUID.randomUUID().toString();
        registerMisloadRaw(retryKey, "INC2", "X1",
                List.of(misloadBag("BD", 99), misloadBag("BE", 3)))
                .andExpect(status().isConflict());
        registerMisloadRaw(retryKey, "INC2", "X1",
                List.of(misloadBag("BE", 3), misloadBag("BD", 3)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentRegisterAndLoad_singleConsistentOutcome() throws Exception {
        setupStandardScenario();

        List<Callable<Object>> tasks = List.of(
                () -> misloadService.register(new RegisterMisloadRequest(
                        UUID.randomUUID().toString(), "INC1", "X1",
                        List.of(misloadBag("BA", 3), misloadBag("BB", 3)))),
                () -> baggageService.load("L2",
                        new BaggageDtos.LoadRequest(UUID.randomUUID().toString(), 1, List.of("BA"))));
        List<Object> results = runConcurrently(tasks);

        long registerSuccess = results.stream()
                .filter(MisloadDtos.RegisterMisloadResponse.class::isInstance).count();
        long loadSuccess = results.stream()
                .filter(BaggageDtos.LoadResponse.class::isInstance).count();
        assertThat(registerSuccess + loadSuccess).isEqualTo(1);

        Integer incidents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class);
        Integer loaded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BA'", Integer.class);
        if (registerSuccess == 1) {
            // 错装先提交：装载被拒，无装载记录
            assertThat(incidents).isEqualTo(1);
            assertThat(loaded).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BA'", String.class))
                    .isEqualTo("MISLOADED");
        } else {
            // 装载先提交：错装整单版本冲突回滚
            assertThat(incidents).isZero();
            assertThat(loaded).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BA'", String.class))
                    .isEqualTo("IN_TRANSIT");
        }
    }

    @Test
    void concurrentConfirmAndSeal_orSealedLegRejectsReroute() throws Exception {
        setupStandardScenario();
        misloadService.register(new RegisterMisloadRequest(
                UUID.randomUUID().toString(), "INC1", "X1",
                List.of(misloadBag("BA", 3), misloadBag("BB", 3))));
        misloadService.preview("INC1", new PreviewRerouteRequest(
                UUID.randomUUID().toString(),
                List.of(new RerouteBagRequest("BA", List.of("R1", "R2")),
                        new RerouteBagRequest("BB", List.of("R1", "R2", "R3")))));

        List<Callable<Object>> tasks = List.of(
                () -> misloadService.confirm("INC1",
                        new ConfirmRerouteRequest(UUID.randomUUID().toString())),
                () -> baggageService.seal("R1",
                        new BaggageDtos.SealRequest(UUID.randomUUID().toString(), 1)));
        List<Object> results = runConcurrently(tasks);

        long confirmSuccess = results.stream()
                .filter(MisloadDtos.ConfirmRerouteResponse.class::isInstance).count();
        long confirmRejected = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 422)
                .count();
        assertThat(confirmSuccess + confirmRejected).isEqualTo(1);

        String r1Status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'R1'", String.class);
        assertThat(r1Status).isEqualTo("SEALED");
        String incidentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM misload_incident WHERE incident_key = 'INC1'", String.class);
        if (confirmSuccess == 1) {
            // 改派先提交：封舱随后正常完成
            assertThat(incidentStatus).isEqualTo("CLOSED");
        } else {
            // 封舱先提交：整单改派拒绝，无部分改派、无快照
            assertThat(incidentStatus).isEqualTo("OPEN");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM path_snapshot", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT path_generation FROM bag WHERE bag_tag = 'BA'", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BA'", String.class))
                    .isEqualTo("MISLOADED");
        }
    }

    @Test
    void concurrentDoubleConfirm_onlyOneCloses() throws Exception {
        setupStandardScenario();
        misloadService.register(new RegisterMisloadRequest(
                UUID.randomUUID().toString(), "INC1", "X1",
                List.of(misloadBag("BA", 3), misloadBag("BB", 3))));
        misloadService.preview("INC1", new PreviewRerouteRequest(
                UUID.randomUUID().toString(),
                List.of(new RerouteBagRequest("BA", List.of("R1", "R2")),
                        new RerouteBagRequest("BB", List.of("R1", "R2", "R3")))));

        List<Callable<Object>> tasks = List.of(
                () -> misloadService.confirm("INC1",
                        new ConfirmRerouteRequest(UUID.randomUUID().toString())),
                () -> misloadService.confirm("INC1",
                        new ConfirmRerouteRequest(UUID.randomUUID().toString())));
        List<Object> results = runConcurrently(tasks);

        long success = results.stream()
                .filter(MisloadDtos.ConfirmRerouteResponse.class::isInstance).count();
        long conflict = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT path_generation FROM bag WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_snapshot", Integer.class)).isEqualTo(8);
    }

    @Test
    void concurrentSameRegisterRequestId_singleEffect() throws Exception {
        setupStandardScenario();
        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> misloadService.register(new RegisterMisloadRequest(
                    requestId, "INC1", "X1",
                    List.of(misloadBag("BA", 3), misloadBag("BB", 3)))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(result ->
                assertThat(result).isInstanceOf(MisloadDtos.RegisterMisloadResponse.class));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_item", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    void queries_incidentViewAndLineageAreReadOnly() throws Exception {
        setupStandardScenario();
        registerMisload("INC1", "X1", List.of(misloadBag("BA", 3), misloadBag("BB", 3)))
                .andExpect(status().isCreated());
        preview("INC1", List.of(path("BA", List.of("R1", "R2")),
                path("BB", List.of("R1", "R2", "R3")))).andExpect(status().isOk());

        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentKey").value("INC1"))
                .andExpect(jsonPath("$.actualLegId").value("X1"))
                .andExpect(jsonPath("$.arrivalStation").value("SHA"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BA"))
                .andExpect(jsonPath("$.items[0].bagVersion").value(3))
                .andExpect(jsonPath("$.items[0].scanStation").value("SHA"))
                .andExpect(jsonPath("$.preview", hasSize(2)));

        // 确认后视图转 CLOSED 且含 closedAt
        confirm("INC1").andExpect(status().isOk());
        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists());

        // 只读：未知事件/未知行李 404
        mockMvc.perform(get("/api/misloads/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/misloads/bags/NOPE/lineage")).andExpect(status().isNotFound());
    }

    // ------ 场景与辅助方法 ------

    /**
     * 标准场景：L1 PEK->SHA、L2 SHA->CAN、L3 CAN->SZX 为原始行程航段；
     * X1 HGH->SHA 为不属于行程的实际错运航段；
     * R1 SHA->WUH、R2 WUH->CAN、R3 CAN->SZX 为恢复航段（出发时间严格递增）；
     * S1/S2 缺出发时间、T1/T2 出发时间逆序，用于失败分支。
     * BA 行程 [L1,L2]、BB 行程 [L1,L2,L3]，均已乘 L1 到达 SHA，当前版本 3。
     */
    private void setupStandardScenario() throws Exception {
        registerLeg("L1", "PEK", "SHA", null);
        registerLeg("L2", "SHA", "CAN", null);
        registerLeg("L3", "CAN", "SZX", null);
        registerLeg("F1", "PEK", "SHA", null);
        registerLeg("F2", "PEK", "SHA", null);
        registerLeg("X1", "HGH", "SHA", "2026-09-23T06:00:00Z");
        registerLeg("R1", "SHA", "WUH", "2026-09-25T08:00:00Z");
        registerLeg("R2", "WUH", "CAN", "2026-09-25T12:00:00Z");
        registerLeg("R3", "CAN", "SZX", "2026-09-25T16:00:00Z");
        registerLeg("S1", "SHA", "WUH", null);
        registerLeg("S2", "WUH", "CAN", "2026-09-25T12:00:00Z");
        registerLeg("T1", "SHA", "WUH", "2026-09-25T10:00:00Z");
        registerLeg("T2", "WUH", "CAN", "2026-09-25T09:00:00Z");
        registerBag("BA", List.of("L1", "L2"));
        registerBag("BB", List.of("L1", "L2", "L3"));
        load("L1", 1, List.of("BA", "BB")).andExpect(status().isOk());
        seal("L1", 2).andExpect(status().isOk());
        arriveExact("L1", List.of("BA", "BB")).andExpect(status().isOk());
    }

    /** 让一件行李独自走某航段：装载、封舱、精确到达。 */
    private void flyBagTo(String bagTag, String legId) throws Exception {
        int legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
        load(legId, legVersion, List.of(bagTag)).andExpect(status().isOk());
        seal(legId, legVersion + 1).andExpect(status().isOk());
        arriveExact(legId, List.of(bagTag)).andExpect(status().isOk());
    }

    private void assertNoMisloadAndBagsUntouched() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_item", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE status = 'MISLOADED'", Integer.class)).isZero();
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      String departureTime) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination,
                "departureTime", departureTime == null ? "" : departureTime));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arriveExact(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private static MisloadBagItem misloadBag(String bagTag, int expectedVersion) {
        return new MisloadBagItem(bagTag, expectedVersion, "SHA");
    }

    private static Map<String, Object> path(String bagTag, List<String> legs) {
        return Map.of("bagTag", bagTag, "path", legs);
    }

    private ResultActions registerMisload(String incidentKey, String actualLegId,
                                          List<MisloadBagItem> bags) throws Exception {
        return registerMisloadRaw(UUID.randomUUID().toString(), incidentKey, actualLegId, bags);
    }

    private ResultActions registerMisloadRaw(String requestId, String incidentKey, String actualLegId,
                                             List<MisloadBagItem> bags) throws Exception {
        return postJson("/api/misloads", Map.of("requestId", requestId,
                "incidentKey", incidentKey, "actualLegId", actualLegId, "bags", bags));
    }

    private ResultActions preview(String incidentKey, List<Map<String, Object>> bags) throws Exception {
        return previewRaw(UUID.randomUUID().toString(), incidentKey, bags);
    }

    private ResultActions previewRaw(String requestId, String incidentKey,
                                     List<Map<String, Object>> bags) throws Exception {
        return postJson("/api/misloads/" + incidentKey + "/reroute/preview",
                Map.of("requestId", requestId, "bags", bags));
    }

    private ResultActions confirm(String incidentKey) throws Exception {
        return confirmRaw(UUID.randomUUID().toString(), incidentKey);
    }

    private ResultActions confirmRaw(String requestId, String incidentKey) throws Exception {
        return postJson("/api/misloads/" + incidentKey + "/reroute/confirm",
                Map.of("requestId", requestId));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
