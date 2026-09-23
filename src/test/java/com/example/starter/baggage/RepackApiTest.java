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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容器重封 API 测试（真实 H2 MySQL 兼容库）：覆盖拆分、合并、清单差异预览、
 * 双人约束、版本/封签/集合变化与短卸/卸载分支整体回滚、幂等同参重放与异参 409、
 * 全局唯一约束、容器链保留与证据稳定排序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RepackApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM repack_evidence");
        jdbcTemplate.update("DELETE FROM repack_order");
        jdbcTemplate.update("DELETE FROM bag_container_chain");
        jdbcTemplate.update("DELETE FROM baggage_container");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void split_oneSourceIntoTwoTargetsRebindsAllBagsAtomically() throws Exception {
        seedLegWithSealedContainers();

        // C1 含 BAG1、BAG2，拆到 T1、T2
        createRepack("RP-1",
                List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1")),
                        target("T2", "SEAL-T2", List.of("BAG2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PREVIEW"))
                .andExpect(jsonPath("$.totalBags").value(2))
                .andExpect(jsonPath("$.scanStatuses[0].scanStatus").value("IN_CONTAINER"))
                .andExpect(jsonPath("$.differences", hasSize(2)));

        activate("RP-1", "op-001", "rev-001").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sources[0].status").value("CLOSED_REPACKED"))
                .andExpect(jsonPath("$.sources[0].version").value(2))
                .andExpect(jsonPath("$.targets[0].status").value("SEALED"))
                .andExpect(jsonPath("$.targets[0].version").value(1));

        // 源容器关闭、目标容器封舱，行李一次性改绑
        mockMvc.perform(get("/api/containers/C1"))
                .andExpect(jsonPath("$.status").value("CLOSED_REPACKED"))
                .andExpect(jsonPath("$.bagTags", hasSize(0)));
        mockMvc.perform(get("/api/containers/T1"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealNo").value("SEAL-T1"))
                .andExpect(jsonPath("$.bagTags", contains("BAG1")));
        mockMvc.perform(get("/api/containers/T2"))
                .andExpect(jsonPath("$.bagTags", contains("BAG2")));

        // 原容器链逐项保留：每件行李 C1 -> T* 两行
        Integer chainB1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_container_chain WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(chainB1).isEqualTo(2);
        List<String> chainB2 = jdbcTemplate.queryForList(
                "SELECT container_no FROM bag_container_chain WHERE bag_tag = 'BAG2' ORDER BY seq",
                String.class);
        assertThat(chainB2).containsExactly("C1", "T2");

        // 证据只读且按 bagTag 稳定排序
        mockMvc.perform(get("/api/repacks/RP-1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.entries[0].sourceContainerNo").value("C1"))
                .andExpect(jsonPath("$.entries[0].targetContainerNo").value("T1"))
                .andExpect(jsonPath("$.entries[0].oldSealNo").value("SEAL-C1"))
                .andExpect(jsonPath("$.entries[0].newSealNo").value("SEAL-T1"))
                .andExpect(jsonPath("$.operatorId").value("op-001"))
                .andExpect(jsonPath("$.reviewerId").value("rev-001"));

        mockMvc.perform(get("/api/repacks/RP-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sources[0].bagTags", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.targets[1].bagTags", contains("BAG2")));
    }

    @Test
    void merge_twoSourcesIntoOneTargetMovesAllBags() throws Exception {
        seedLegWithSealedContainers();

        createRepack("RP-MERGE",
                List.of(source("C1", 1, "SEAL-C1"), source("C2", 1, "SEAL-C2")),
                List.of(target("M1", "SEAL-M1", List.of("BAG2", "BAG4", "BAG3", "BAG1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalBags").value(4))
                .andExpect(jsonPath("$.targets[0].bagTags", contains("BAG1", "BAG2", "BAG3", "BAG4")))
                .andExpect(jsonPath("$.targets[0].fromSources", hasSize(2)));

        activate("RP-MERGE", "op-001", "rev-001").andExpect(status().isOk());

        mockMvc.perform(get("/api/containers/M1"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bagTags", hasSize(4)));
        mockMvc.perform(get("/api/containers/C1")).andExpect(jsonPath("$.status").value("CLOSED_REPACKED"));
        mockMvc.perform(get("/api/containers/C2")).andExpect(jsonPath("$.status").value("CLOSED_REPACKED"));

        // 没有任何一件行李无容器或同时属于两个有效容器
        Integer nullContainer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE container_no IS NULL", Integer.class);
        Integer validBindings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag b JOIN baggage_container c ON b.container_no = c.container_no"
                        + " WHERE c.status = 'SEALED'", Integer.class);
        assertThat(nullContainer).isZero();
        assertThat(validBindings).isEqualTo(4);

        List<String> chains = jdbcTemplate.queryForList(
                "SELECT container_no FROM bag_container_chain WHERE bag_tag = 'BAG3' ORDER BY seq",
                String.class);
        assertThat(chains).containsExactly("C2", "M1");
    }

    @Test
    void createRejectsPartitionThatIsNotExact() throws Exception {
        seedLegWithSealedContainers();

        // 遗漏 BAG2
        createRepack("RP-BAD1",
                List.of(source("C1", 1, "SEAL-C1"), source("C2", 1, "SEAL-C2")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG3")),
                        target("T2", "SEAL-T2", List.of("BAG4"))))
                .andExpect(status().isUnprocessableEntity());

        // 混入外部行李
        createRepack("RP-BAD2",
                List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG9"))))
                .andExpect(status().isUnprocessableEntity());

        // 分区互斥失败：BAG1 进入两个目标
        createRepack("RP-BAD3",
                List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1")),
                        target("T2", "SEAL-T2", List.of("BAG1", "BAG2"))))
                .andExpect(status().isUnprocessableEntity());

        Integer orders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repack_order", Integer.class);
        assertThat(orders).isZero();
    }

    @Test
    void createRejectsWrongLegPointStateVersionAndSeal() throws Exception {
        seedLegWithSealedContainers();

        // 源容器不存在
        createRepack("RP-X1", List.of(source("NOPE", 1, "SEAL-X")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG2"))))
                .andExpect(status().isNotFound());

        // 版本不符 -> 409
        createRepack("RP-X2", List.of(source("C1", 9, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG2"))))
                .andExpect(status().isConflict());

        // 旧封签不符 -> 422
        createRepack("RP-X3", List.of(source("C1", 1, "WRONG-SEAL")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG2"))))
                .andExpect(status().isUnprocessableEntity());

        // 目标编号/封签已存在
        createRepack("RP-X4", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("C1", "SEAL-NEW", List.of("BAG1", "BAG2"))))
                .andExpect(status().isConflict());
        createRepack("RP-X5", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-C2", List.of("BAG1", "BAG2"))))
                .andExpect(status().isConflict());
    }

    @Test
    void activateRejectsSamePersonAndDoesNotOccupyIdempotencyKey() throws Exception {
        seedLegWithSealedContainers();
        createRepack("RP-2P", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1")),
                        target("T2", "SEAL-T2", List.of("BAG2")))).andExpect(status().isCreated());

        String requestId = UUID.randomUUID().toString();
        // 同一人操作与复核 -> 422，失败不占键
        activateWithId(requestId, "RP-2P", "same-person", "same-person")
                .andExpect(status().isUnprocessableEntity());
        // 同键改为两名不同人员后成功
        activateWithId(requestId, "RP-2P", "op-001", "rev-001").andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value("op-001"));

        // 已激活单再次激活 -> 409
        activate("RP-2P", "op-002", "rev-002").andExpect(status().isConflict());

        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void activateRollsBackWhenBagReportedShort() throws Exception {
        seedLegWithSealedContainers();
        createRepack("RP-SHORT",
                List.of(source("C1", 1, "SEAL-C1"), source("C2", 1, "SEAL-C2")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG2", "BAG3", "BAG4"))))
                .andExpect(status().isCreated());

        // 差异到达：全部报短卸，容器仍 SEALED 且仍绑定行李
        postJson("/api/legs/LEG1/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "bagTags", List.of()))
                .andExpect(status().isOk());

        // 激活整单 422，无目标容器、无证据、源容器仍 SEALED
        activate("RP-SHORT", "op-001", "rev-001").andExpect(status().isUnprocessableEntity());
        assertNoPartialRepack("RP-SHORT", List.of("C1", "C2"), List.of("T1"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM baggage_container WHERE container_no = 'C1'", String.class))
                .isEqualTo("SEALED");
    }

    @Test
    void activateRollsBackWhenBagsUnloaded() throws Exception {
        seedLegWithSealedContainers();
        createRepack("RP-UNLOAD",
                List.of(source("C1", 1, "SEAL-C1"), source("C2", 1, "SEAL-C2")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1", "BAG2", "BAG3", "BAG4"))))
                .andExpect(status().isCreated());

        // 精确到达：行李全部卸载，源容器变 UNLOADED
        postJson("/api/legs/LEG1/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTags", List.of("BAG1", "BAG2", "BAG3", "BAG4")))
                .andExpect(status().isOk());

        activate("RP-UNLOAD", "op-001", "rev-001").andExpect(status().isConflict());
        // 精确到达后源容器已合法收口为 UNLOADED，激活失败不得产生目标容器或证据
        assertNoPartialRepack("RP-UNLOAD", List.of(), List.of("T1"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM baggage_container WHERE container_no = 'C1'", String.class))
                .isEqualTo("UNLOADED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM baggage_container WHERE container_no = 'C2'", String.class))
                .isEqualTo("UNLOADED");
    }

    @Test
    void activateRollsBackWhenBagHandedToNextLeg() throws Exception {
        seedLegWithSealedContainers();
        createRepack("RP-NEXT", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("T1", "SEAL-T1", List.of("BAG1")),
                        target("T2", "SEAL-T2", List.of("BAG2")))).andExpect(status().isCreated());

        // 直接模拟并发提交后行李已转交下一航段（仍占位在源容器的异常中间态，用于激活重读防御分支）
        jdbcTemplate.update("UPDATE bag SET loaded_leg_id = 'LEG2' WHERE bag_tag = 'BAG1'");
        activate("RP-NEXT", "op-001", "rev-001").andExpect(status().isUnprocessableEntity());
        assertNoPartialRepack("RP-NEXT", List.of("C1"), List.of("T1", "T2"));
    }

    @Test
    void activateRollsBackWhenSourceVersionOrSetChanged() throws Exception {
        seedLegWithSealedContainers();

        // RP-A 先成功重封 C1，RP-B 创建时的版本/集合随后变化
        createRepack("RP-A", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("A1", "SEAL-A1", List.of("BAG1")),
                        target("A2", "SEAL-A2", List.of("BAG2")))).andExpect(status().isCreated());
        createRepack("RP-B", List.of(source("C1", 1, "SEAL-C1")),
                List.of(target("B1", "SEAL-B1", List.of("BAG1", "BAG2")))).andExpect(status().isCreated());

        activate("RP-A", "op-a", "rev-a").andExpect(status().isOk());
        // C1 已 CLOSED_REPACKED 且版本变为 2，RP-B 整单 409 且不产生 B1
        activate("RP-B", "op-b", "rev-b").andExpect(status().isConflict());
        Integer b1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage_container WHERE container_no = 'B1'", Integer.class);
        assertThat(b1).isZero();
    }

    @Test
    void idempotency_createReplaysSnapshotIgnoringOrderAndDifferentParams409() throws Exception {
        seedLegWithSealedContainers();

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> first = Map.of(
                "requestId", requestId, "repackKey", "RP-IDEM",
                "sources", List.of(Map.of("containerNo", "C1", "expectedVersion", 1, "sealNo", "SEAL-C1"),
                        Map.of("containerNo", "C2", "expectedVersion", 1, "sealNo", "SEAL-C2")),
                "targets", List.of(
                        Map.of("containerNo", "T1", "newSealNo", "SEAL-T1",
                                "bagTags", List.of("BAG2", "BAG1")),
                        Map.of("containerNo", "T2", "newSealNo", "SEAL-T2",
                                "bagTags", List.of("BAG4", "BAG3"))));
        postJson("/api/repacks", first).andExpect(status().isCreated())
                .andExpect(jsonPath("$.repackKey").value("RP-IDEM"));

        // 容器换序、bagTag 换序视为同参，重放首次快照
        Map<String, Object> reordered = Map.of(
                "requestId", requestId, "repackKey", "RP-IDEM",
                "sources", List.of(Map.of("containerNo", "C2", "expectedVersion", 1, "sealNo", "SEAL-C2"),
                        Map.of("containerNo", "C1", "expectedVersion", 1, "sealNo", "SEAL-C1")),
                "targets", List.of(
                        Map.of("containerNo", "T2", "newSealNo", "SEAL-T2",
                                "bagTags", List.of("BAG3", "BAG4")),
                        Map.of("containerNo", "T1", "newSealNo", "SEAL-T1",
                                "bagTags", List.of("BAG1", "BAG2"))));
        postJson("/api/repacks", reordered).andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets[0].containerNo").value("T1"));

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repack_order WHERE repack_key = 'RP-IDEM'", Integer.class);
        assertThat(orders).isEqualTo(1);

        // 同键异参（分区变化）-> 409
        Map<String, Object> different = Map.of(
                "requestId", requestId, "repackKey", "RP-IDEM",
                "sources", List.of(Map.of("containerNo", "C1", "expectedVersion", 1, "sealNo", "SEAL-C1")),
                "targets", List.of(Map.of("containerNo", "T1", "newSealNo", "SEAL-T1",
                        "bagTags", List.of("BAG1", "BAG2"))));
        postJson("/api/repacks", different).andExpect(status().isConflict());

        // 激活同参重放：返回首次结果，证据只写一次
        String activateId = UUID.randomUUID().toString();
        activateWithId(activateId, "RP-IDEM", "op-1", "rev-1").andExpect(status().isOk());
        activateWithId(activateId, "RP-IDEM", "op-1", "rev-1").andExpect(status().isOk());
        Integer evidence = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repack_evidence WHERE repack_key = 'RP-IDEM'", Integer.class);
        assertThat(evidence).isEqualTo(4);
    }

    @Test
    void packEnforcesGlobalUniqueContainerNoAndSeal() throws Exception {
        seedLegWithSealedContainers();

        pack("C1", "SEAL-XX", List.of("BAG1")).andExpect(status().isConflict());
        pack("C9", "SEAL-C1", List.of("BAG1")).andExpect(status().isConflict());
        // 已在有效容器中的行李不能重复封装
        pack("C9", "SEAL-XX", List.of("BAG1")).andExpect(status().isConflict());
    }

    @Test
    void evidenceAndDetail404ForUnknownKey() throws Exception {
        mockMvc.perform(get("/api/repacks/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/repacks/NOPE/evidence")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/containers/NOPE")).andExpect(status().isNotFound());
    }

    /** 注册航段、4 件行李、装载、封舱并封装两个 SEALED 容器 C1={BAG1,BAG2} C2={BAG3,BAG4}。 */
    private void seedLegWithSealedContainers() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        for (String bag : List.of("BAG1", "BAG2", "BAG3", "BAG4")) {
            registerBag(bag, List.of("LEG1"));
        }
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1,
                "bagTags", List.of("BAG1", "BAG2", "BAG3", "BAG4")))
                .andExpect(status().isOk());
        postJson("/api/legs/LEG1/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", 2))
                .andExpect(status().isOk());
        pack("C1", "SEAL-C1", List.of("BAG2", "BAG1")).andExpect(status().isCreated());
        pack("C2", "SEAL-C2", List.of("BAG4", "BAG3")).andExpect(status().isCreated());
    }

    private void assertNoPartialRepack(String repackKey, List<String> sources, List<String> targets) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM repack_order WHERE repack_key = ?", String.class, repackKey);
        assertThat(status).isEqualTo("PREVIEW");
        Integer evidence = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repack_evidence WHERE repack_key = ?", Integer.class, repackKey);
        assertThat(evidence).isZero();
        for (String target : targets) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM baggage_container WHERE container_no = ?",
                    Integer.class, target);
            assertThat(count).as("失败激活不得创建目标容器 " + target).isZero();
        }
        for (String source : sources) {
            String sourceStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM baggage_container WHERE container_no = ?",
                    String.class, source);
            assertThat(sourceStatus).as("失败激活不得改变源容器状态 " + source).isEqualTo("SEALED");
        }
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions pack(String containerNo, String sealNo, List<String> bagTags) throws Exception {
        return postJson("/api/containers", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "containerNo", containerNo, "legId", "LEG1", "handoverPoint", "PEK-T1",
                "sealNo", sealNo, "bagTags", bagTags));
    }

    private static Map<String, Object> source(String containerNo, int version, String sealNo) {
        return Map.of("containerNo", containerNo, "expectedVersion", version, "sealNo", sealNo);
    }

    private static Map<String, Object> target(String containerNo, String sealNo, List<String> bagTags) {
        return Map.of("containerNo", containerNo, "newSealNo", sealNo, "bagTags", bagTags);
    }

    private ResultActions createRepack(String key, List<Map<String, Object>> sources,
                                       List<Map<String, Object>> targets) throws Exception {
        return postJson("/api/repacks", Map.of(
                "requestId", UUID.randomUUID().toString(), "repackKey", key,
                "sources", sources, "targets", targets));
    }

    private ResultActions activate(String key, String operator, String reviewer) throws Exception {
        return activateWithId(UUID.randomUUID().toString(), key, operator, reviewer);
    }

    private ResultActions activateWithId(String requestId, String key,
                                         String operator, String reviewer) throws Exception {
        return postJson("/api/repacks/" + key + "/activate", Map.of(
                "requestId", requestId, "operatorId", operator, "reviewerId", reviewer));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
