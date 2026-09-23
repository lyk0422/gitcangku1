package com.example.starter.baggage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容器重封单 API 测试：覆盖容器生命周期、拆分、合并、清单差异预览、
 * 双人确认约束、激活整体回滚、幂等重放与证据查询稳定排序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerResealApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_container_history");
        jdbcTemplate.update("DELETE FROM container_bag");
        jdbcTemplate.update("DELETE FROM container");
        jdbcTemplate.update("DELETE FROM reseal_order");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    // ---------- 容器生命周期 ----------

    @Test
    void containerLifecycle_createLoadSealAndQuery() throws Exception {
        setupLegWithBags();
        createContainer("C1", "LEG1", "HP1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
        // 重复容器编号 -> 409；引用不存在航段 -> 422
        createContainer("C1", "LEG1", "HP1").andExpect(status().isConflict());
        createContainer("C9", "LEG_MISSING", "HP1").andExpect(status().isUnprocessableEntity());

        containerLoad("C1", 1, List.of("BAG01", "BAG02")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02")));

        containerSeal("C1", 2, "SEAL-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.sealNo").value("SEAL-1"))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02")));

        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02")));
        mockMvc.perform(get("/api/containers/NOPE")).andExpect(status().isNotFound());

        // 封签后禁止装箱与重复封签
        containerLoad("C1", 3, List.of("BAG03")).andExpect(status().isUnprocessableEntity());
        containerSeal("C1", 3, "SEAL-X").andExpect(status().isUnprocessableEntity());
        // 封签号全局唯一
        createContainer("C2", "LEG1", "HP1").andExpect(status().isCreated());
        containerSeal("C2", 1, "SEAL-1").andExpect(status().isConflict());
    }

    @Test
    void containerLoad_batchAtomicAndRejectsInvalidBags() throws Exception {
        setupLegWithBags();
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("BAG09", List.of("LEG2")).andExpect(status().isCreated());
        createContainer("C1", "LEG1", "HP1").andExpect(status().isCreated());

        // 版本冲突 -> 409
        containerLoad("C1", 99, List.of("BAG01")).andExpect(status().isConflict());
        // 请求内重复 -> 422
        containerLoad("C1", 1, List.of("BAG01", "BAG01")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        containerLoad("C1", 1, List.of("BAG_X")).andExpect(status().isUnprocessableEntity());
        // 未装载到本航段 -> 422
        containerLoad("C1", 1, List.of("BAG09")).andExpect(status().isUnprocessableEntity());
        // 容器不存在 -> 404
        containerLoad("NOPE", 1, List.of("BAG01")).andExpect(status().isNotFound());

        // 整批原子：BAG01 合法、BAG09 非法，整批 422 且 BAG01 不入箱
        containerLoad("C1", 1, List.of("BAG01", "BAG09")).andExpect(status().isUnprocessableEntity());
        Integer boxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag", Integer.class);
        assertThat(boxCount).isZero();
        mockMvc.perform(get("/api/containers/C1")).andExpect(jsonPath("$.version").value(1));

        // 已入箱行李不得再入其他容器
        containerLoad("C1", 1, List.of("BAG01")).andExpect(status().isOk());
        createContainer("C2", "LEG1", "HP1").andExpect(status().isCreated());
        containerLoad("C2", 1, List.of("BAG01")).andExpect(status().isUnprocessableEntity());
    }

    // ---------- 重封主流程：拆分与合并 ----------

    @Test
    void reseal_splitOneSourceToTwoTargets() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));

        createOrder("RK-1", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "SEAL-N1", List.of("BAG01")),
                        target("T2", "SEAL-N2", List.of("BAG02"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.legId").value("LEG1"))
                .andExpect(jsonPath("$.handoverPoint").value("HP1"))
                .andExpect(jsonPath("$.preview.sourceManifests", hasSize(1)))
                .andExpect(jsonPath("$.preview.sourceManifests[0].bagTags",
                        contains("BAG01", "BAG02")))
                .andExpect(jsonPath("$.preview.missing", hasSize(0)))
                .andExpect(jsonPath("$.preview.external", hasSize(0)))
                .andExpect(jsonPath("$.preview.bagStates", hasSize(2)))
                .andExpect(jsonPath("$.preview.bagStates[0].bagTag").value("BAG01"))
                .andExpect(jsonPath("$.preview.bagStates[0].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.preview.bagStates[0].loadedLegId").value("LEG1"));

        // 创建只预览：源容器与行李均未改变
        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02")));

        // 操作人先确认：仍待复核
        confirm("RK-1", "op1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.operatorConfirmed").value(true))
                .andExpect(jsonPath("$.reviewerConfirmed").value(false));

        // 复核人确认：同一事务内原子激活
        confirm("RK-1", "rev1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.operatorConfirmed").value(true))
                .andExpect(jsonPath("$.reviewerConfirmed").value(true))
                .andExpect(jsonPath("$.beforeSnapshot[0].containerId").value("C1"))
                .andExpect(jsonPath("$.beforeSnapshot[0].sealNo").value("SEAL-1"))
                .andExpect(jsonPath("$.beforeSnapshot[0].bagTags", contains("BAG01", "BAG02")))
                .andExpect(jsonPath("$.afterSnapshot", hasSize(2)))
                .andExpect(jsonPath("$.afterSnapshot[0].containerId").value("T1"))
                .andExpect(jsonPath("$.afterSnapshot[0].sealNo").value("SEAL-N1"))
                .andExpect(jsonPath("$.afterSnapshot[0].bagTags", contains("BAG01")))
                .andExpect(jsonPath("$.afterSnapshot[1].containerId").value("T2"))
                .andExpect(jsonPath("$.afterSnapshot[1].bagTags", contains("BAG02")))
                .andExpect(jsonPath("$.activatedAt").exists());

        // 源容器统一 CLOSED_REPACKED，目标容器统一 SEALED
        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(jsonPath("$.status").value("CLOSED_REPACKED"))
                .andExpect(jsonPath("$.sealNo").value("SEAL-1"))
                .andExpect(jsonPath("$.bags", hasSize(0)));
        mockMvc.perform(get("/api/containers/T1"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealNo").value("SEAL-N1"))
                .andExpect(jsonPath("$.bags", contains("BAG01")));
        mockMvc.perform(get("/api/containers/T2"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bags", contains("BAG02")));

        // 行李一次性改绑且只属于一个有效容器，逐项保留原容器链
        Integer mappingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag WHERE bag_tag IN ('BAG01','BAG02')",
                Integer.class);
        assertThat(mappingCount).isEqualTo(2);
        mockMvc.perform(get("/api/bags/BAG01/container-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain", hasSize(2)))
                .andExpect(jsonPath("$.chain[0].containerId").value("C1"))
                .andExpect(jsonPath("$.chain[1].containerId").value("T1"));
        mockMvc.perform(get("/api/bags/BAG02/container-chain"))
                .andExpect(jsonPath("$.chain[1].containerId").value("T2"));
    }

    @Test
    void reseal_mergeTwoSourcesToOneTarget() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        sealedContainer("C2", "SEAL-2", List.of("BAG03", "BAG04"));

        // 源容器与袋号乱序提交，视为同参且响应稳定排序
        createOrder("RK-2", "op1", "rev1",
                List.of(source("C2", 3, "SEAL-2"), source("C1", 3, "SEAL-1")),
                List.of(target("T9", "SEAL-N9", List.of("BAG04", "BAG01", "BAG03", "BAG02"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sources[0].containerId").value("C1"))
                .andExpect(jsonPath("$.sources[1].containerId").value("C2"))
                .andExpect(jsonPath("$.targets[0].bagTags",
                        contains("BAG01", "BAG02", "BAG03", "BAG04")))
                .andExpect(jsonPath("$.preview.missing", hasSize(0)))
                .andExpect(jsonPath("$.preview.external", hasSize(0)));

        confirm("RK-2", "op1").andExpect(status().isOk());
        confirm("RK-2", "rev1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.beforeSnapshot", hasSize(2)))
                .andExpect(jsonPath("$.afterSnapshot[0].bagTags",
                        contains("BAG01", "BAG02", "BAG03", "BAG04")));

        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(jsonPath("$.status").value("CLOSED_REPACKED"));
        mockMvc.perform(get("/api/containers/C2"))
                .andExpect(jsonPath("$.status").value("CLOSED_REPACKED"));
        mockMvc.perform(get("/api/containers/T9"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02", "BAG03", "BAG04")));
        mockMvc.perform(get("/api/bags/BAG04/container-chain"))
                .andExpect(jsonPath("$.chain[0].containerId").value("C2"))
                .andExpect(jsonPath("$.chain[1].containerId").value("T9"));
    }

    // ---------- 创建预览与结构校验 ----------

    @Test
    void create_previewsManifestDiffAndScanStates() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));

        // 目标遗漏 BAG02、混入外部袋号 BAG99：创建仍成功，预览给出差异
        createOrder("RK-3", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "SEAL-N1", List.of("BAG01", "BAG99"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview.missing", contains("BAG02")))
                .andExpect(jsonPath("$.preview.external", contains("BAG99")))
                .andExpect(jsonPath("$.preview.bagStates[1].bagTag").value("BAG99"))
                .andExpect(jsonPath("$.preview.bagStates[1].status").value("NOT_FOUND"));
        // 创建不改变容器与行李
        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bags", contains("BAG01", "BAG02")));
        mockMvc.perform(get("/api/reseal-orders/RK-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"));
    }

    @Test
    void create_rejectsStructuralErrors() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        createContainer("C2", "LEG1", "HP2").andExpect(status().isCreated());

        // 操作人与复核人相同 -> 422
        createOrder("RK-S1", "op1", "op1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        // 目标集合重复袋号 -> 422
        createOrder("RK-S2", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01")),
                        target("T2", "N2", List.of("BAG01"))))
                .andExpect(status().isUnprocessableEntity());
        // 目标容器编号重复 / 新封签重复 / 与源重叠 -> 422
        createOrder("RK-S3", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01")),
                        target("T1", "N2", List.of("BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        createOrder("RK-S4", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01")),
                        target("T2", "N1", List.of("BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        createOrder("RK-S5", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("C1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        // 源容器不存在 -> 422
        createOrder("RK-S6", "op1", "rev1",
                List.of(source("C_MISSING", 1, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        // 源容器交接点不一致 -> 422
        createOrder("RK-S7", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1"), source("C2", 1, "SEAL-2")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        // 源容器数量越界 -> 400（参数校验）
        createOrder("RK-S8", "op1", "rev1", List.of(),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isBadRequest());
        // 行李总数少于 2 -> 422
        createOrder("RK-S9", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01"))))
                .andExpect(status().isUnprocessableEntity());
        // repackKey 唯一
        createOrder("RK-DUP", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        createOrder("RK-DUP", "op2", "rev2",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T2", "N2", List.of("BAG01", "BAG02"))))
                .andExpect(status().isConflict());
    }

    // ---------- 双人确认约束 ----------

    @Test
    void confirm_requiresTwoDifferentPeople() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        createOrder("RK-4", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());

        // 第三人不得确认 -> 422
        confirm("RK-4", "stranger").andExpect(status().isUnprocessableEntity());
        // 操作人确认后不得再次确认（须两名不同人员） -> 422
        confirm("RK-4", "op1").andExpect(status().isOk());
        confirm("RK-4", "op1").andExpect(status().isUnprocessableEntity());
        // 重封单不存在 -> 404
        confirm("RK-MISSING", "rev1").andExpect(status().isNotFound());
        // 复核人确认后激活；已激活单不得再确认 -> 409
        confirm("RK-4", "rev1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        confirm("RK-4", "op1").andExpect(status().isConflict());
    }

    // ---------- 激活失败整体回滚 ----------

    @Test
    void activation_versionOrSealMismatch409_andNothingChanges() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));

        createOrder("RK-5", "op1", "rev1",
                List.of(source("C1", 99, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        confirm("RK-5", "op1").andExpect(status().isOk());
        confirm("RK-5", "rev1").andExpect(status().isConflict());
        assertNothingChanged("RK-5", "C1");

        createOrder("RK-6", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-WRONG")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        confirm("RK-6", "op1").andExpect(status().isOk());
        confirm("RK-6", "rev1").andExpect(status().isConflict());
        assertNothingChanged("RK-6", "C1");
    }

    @Test
    void activation_partitionMismatch422_fullRollback() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));

        // 目标遗漏 BAG02 且混入外部 BAG03：创建仅预览，激活时整单 422
        createOrder("RK-7", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01")),
                        target("T2", "N2", List.of("BAG03"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview.missing", contains("BAG02")))
                .andExpect(jsonPath("$.preview.external", contains("BAG03")));
        confirm("RK-7", "op1").andExpect(status().isOk());
        confirm("RK-7", "rev1").andExpect(status().isUnprocessableEntity());
        assertNothingChanged("RK-7", "C1");

        // 混入外部行李：整单 422
        createOrder("RK-8", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02")),
                        target("T2", "N2", List.of("BAG03"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview.external", contains("BAG03")));
        confirm("RK-8", "op1").andExpect(status().isOk());
        confirm("RK-8", "rev1").andExpect(status().isUnprocessableEntity());
        assertNothingChanged("RK-8", "C1");
    }

    @Test
    void activation_bagShortUnloaded422_fullRollback() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        createOrder("RK-9", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        confirm("RK-9", "op1").andExpect(status().isOk());

        // 创建后源行李被报短卸：封舱 LEG1 并差异到达（BAG01 缺失）
        sealLeg("LEG1", 2).andExpect(status().isOk());
        arriveDifference("LEG1", 3, List.of("BAG02")).andExpect(status().isOk());

        confirm("RK-9", "rev1").andExpect(status().isUnprocessableEntity());
        // 整单回滚：源容器仍 SEALED、行李仍属源容器、目标未创建、单仍待确认
        assertNothingChanged("RK-9", "C1");
        // 复核人确认随事务回滚，操作人确认保留
        mockMvc.perform(get("/api/reseal-orders/RK-9"))
                .andExpect(jsonPath("$.operatorConfirmed").value(true))
                .andExpect(jsonPath("$.reviewerConfirmed").value(false));
    }

    @Test
    void activation_bagUnloadedAfterExactArrive422() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        createOrder("RK-10", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());

        // 创建后航段精确到达，行李已卸载
        sealLeg("LEG1", 2).andExpect(status().isOk());
        arriveLeg("LEG1", List.of("BAG01", "BAG02", "BAG03", "BAG04")).andExpect(status().isOk());

        confirm("RK-10", "op1").andExpect(status().isOk());
        confirm("RK-10", "rev1").andExpect(status().isUnprocessableEntity());
        assertNothingChanged("RK-10", "C1");
    }

    @Test
    void activation_targetIdOrSealAlreadyExists409() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        sealedContainer("C2", "SEAL-2", List.of("BAG03", "BAG04"));

        // 目标容器编号与现有容器冲突 -> 409
        createOrder("RK-11", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("C2", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        confirm("RK-11", "op1").andExpect(status().isOk());
        confirm("RK-11", "rev1").andExpect(status().isConflict());
        assertNothingChanged("RK-11", "C1");

        // 新封签与现有封签冲突 -> 409
        createOrder("RK-12", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "SEAL-2", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());
        confirm("RK-12", "op1").andExpect(status().isOk());
        confirm("RK-12", "rev1").andExpect(status().isConflict());
        assertNothingChanged("RK-12", "C1");
    }

    // ---------- 幂等与证据查询 ----------

    @Test
    void idempotency_createReplayOrderInsensitiveAnd409OnDifferentParams() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        sealedContainer("C2", "SEAL-2", List.of("BAG03", "BAG04"));

        String requestId = UUID.randomUUID().toString();
        createOrder(requestId, "RK-13", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1"), source("C2", 3, "SEAL-2")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG03")),
                        target("T2", "N2", List.of("BAG02", "BAG04"))))
                .andExpect(status().isCreated());
        // 容器与 bagTag 换序视为同参：重放首次快照
        createOrder(requestId, "RK-13", "op1", "rev1",
                List.of(source("C2", 3, "SEAL-2"), source("C1", 3, "SEAL-1")),
                List.of(target("T2", "N2", List.of("BAG04", "BAG02")),
                        target("T1", "N1", List.of("BAG03", "BAG01"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repackKey").value("RK-13"))
                .andExpect(jsonPath("$.sources[0].containerId").value("C1"));
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reseal_order WHERE repack_key = 'RK-13'", Integer.class);
        assertThat(orderCount).isEqualTo(1);
        // 同键异参 -> 409
        createOrder(requestId, "RK-13", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1"), source("C2", 3, "SEAL-2")),
                List.of(target("T9", "N9", List.of("BAG01", "BAG02", "BAG03", "BAG04"))))
                .andExpect(status().isConflict());
    }

    @Test
    void idempotency_failureDoesNotOccupyKey_andConfirmReplay() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));

        // 创建失败（422）不占键，修正参数后同键成功
        String createRequestId = UUID.randomUUID().toString();
        createOrder(createRequestId, "RK-14", "op1", "op1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isUnprocessableEntity());
        createOrder(createRequestId, "RK-14", "op1", "rev1",
                List.of(source("C1", 3, "SEAL-1")),
                List.of(target("T1", "N1", List.of("BAG01", "BAG02"))))
                .andExpect(status().isCreated());

        // 确认失败（第三人 422）不占键，修正后同键成功
        String confirmRequestId = UUID.randomUUID().toString();
        confirm(confirmRequestId, "RK-14", "stranger").andExpect(status().isUnprocessableEntity());
        confirm(confirmRequestId, "RK-14", "op1").andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorConfirmed").value(true));
        // 同键同参重放：返回首次快照，不产生二次确认
        confirm(confirmRequestId, "RK-14", "op1").andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorConfirmed").value(true))
                .andExpect(jsonPath("$.reviewerConfirmed").value(false));
        // 同键异参 -> 409
        confirm(confirmRequestId, "RK-14", "rev1").andExpect(status().isConflict());
    }

    @Test
    void evidenceQuery_readOnlyStableSortingAnd404() throws Exception {
        setupLegWithBags();
        sealedContainer("C1", "SEAL-1", List.of("BAG01", "BAG02"));
        sealedContainer("C2", "SEAL-2", List.of("BAG03", "BAG04"));
        createOrder("RK-15", "op1", "rev1",
                List.of(source("C2", 3, "SEAL-2"), source("C1", 3, "SEAL-1")),
                List.of(target("T2", "N2", List.of("BAG04", "BAG02")),
                        target("T1", "N1", List.of("BAG03", "BAG01"))))
                .andExpect(status().isCreated());
        confirm("RK-15", "op1").andExpect(status().isOk());
        confirm("RK-15", "rev1").andExpect(status().isOk());

        // 证据查询只读并稳定排序：重复查询结果一致
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/reseal-orders/RK-15"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVATED"))
                    .andExpect(jsonPath("$.sources[0].containerId").value("C1"))
                    .andExpect(jsonPath("$.sources[1].containerId").value("C2"))
                    .andExpect(jsonPath("$.targets[0].containerId").value("T1"))
                    .andExpect(jsonPath("$.targets[0].bagTags", contains("BAG01", "BAG03")))
                    .andExpect(jsonPath("$.targets[1].containerId").value("T2"))
                    .andExpect(jsonPath("$.targets[1].bagTags", contains("BAG02", "BAG04")))
                    .andExpect(jsonPath("$.beforeSnapshot[0].containerId").value("C1"))
                    .andExpect(jsonPath("$.afterSnapshot[1].containerId").value("T2"));
        }
        mockMvc.perform(get("/api/reseal-orders/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/container-chain")).andExpect(status().isNotFound());
    }

    // ---------- 测试辅助 ----------

    /** 登记 LEG1（PEK->SHA）与 BAG01~BAG04，并整批装载到 LEG1（版本推进到 2）。 */
    private void setupLegWithBags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        for (String bagTag : List.of("BAG01", "BAG02", "BAG03", "BAG04")) {
            registerBag(bagTag, List.of("LEG1")).andExpect(status().isCreated());
        }
        loadLeg("LEG1", 1, List.of("BAG01", "BAG02", "BAG03", "BAG04")).andExpect(status().isOk());
    }

    /** 创建容器、装箱并封签，最终版本为 3。 */
    private void sealedContainer(String containerId, String sealNo, List<String> bagTags)
            throws Exception {
        createContainer(containerId, "LEG1", "HP1").andExpect(status().isCreated());
        containerLoad(containerId, 1, bagTags).andExpect(status().isOk());
        containerSeal(containerId, 2, sealNo).andExpect(status().isOk());
    }

    /** 断言激活失败后的整体回滚：源容器与行李归属不变、目标未创建、单仍待确认。 */
    private void assertNothingChanged(String repackKey, String sourceContainer) throws Exception {
        mockMvc.perform(get("/api/containers/" + sourceContainer))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.bags", containsInAnyOrder("BAG01", "BAG02")));
        mockMvc.perform(get("/api/containers/T1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reseal-orders/" + repackKey))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"));
        Integer targetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container WHERE container_id LIKE 'T%'", Integer.class);
        assertThat(targetCount).isZero();
    }

    private Map<String, Object> source(String containerId, int expectedVersion, String sealNo) {
        return Map.of("containerId", containerId, "expectedVersion", expectedVersion,
                "sealNo", sealNo);
    }

    private Map<String, Object> target(String containerId, String newSealNo, List<String> bagTags) {
        return Map.of("containerId", containerId, "newSealNo", newSealNo, "bagTags", bagTags);
    }

    private ResultActions createOrder(String repackKey, String operator, String reviewer,
                                      List<Map<String, Object>> sources,
                                      List<Map<String, Object>> targets) throws Exception {
        return createOrder(UUID.randomUUID().toString(), repackKey, operator, reviewer,
                sources, targets);
    }

    private ResultActions createOrder(String requestId, String repackKey, String operator,
                                      String reviewer, List<Map<String, Object>> sources,
                                      List<Map<String, Object>> targets) throws Exception {
        return postJson("/api/reseal-orders", Map.of(
                "requestId", requestId, "repackKey", repackKey,
                "operatorId", operator, "reviewerId", reviewer,
                "sources", sources, "targets", targets));
    }

    private ResultActions confirm(String repackKey, String confirmerId) throws Exception {
        return confirm(UUID.randomUUID().toString(), repackKey, confirmerId);
    }

    private ResultActions confirm(String requestId, String repackKey, String confirmerId)
            throws Exception {
        return postJson("/api/reseal-orders/" + repackKey + "/confirm",
                Map.of("requestId", requestId, "confirmerId", confirmerId));
    }

    private ResultActions registerLeg(String legId, String origin, String destination)
            throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions loadLeg(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions sealLeg(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arriveLeg(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions arriveDifference(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions createContainer(String containerId, String legId, String handoverPoint)
            throws Exception {
        return postJson("/api/containers", Map.of("requestId", UUID.randomUUID().toString(),
                "containerId", containerId, "legId", legId, "handoverPoint", handoverPoint));
    }

    private ResultActions containerLoad(String containerId, int expectedVersion,
                                        List<String> bagTags) throws Exception {
        return postJson("/api/containers/" + containerId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions containerSeal(String containerId, int expectedVersion, String sealNo)
            throws Exception {
        return postJson("/api/containers/" + containerId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "sealNo", sealNo));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
