package com.example.starter.baggage;

import java.util.LinkedHashMap;
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
 * 平面舱位布局装载测试：合成 10x10 舱底与 5x10 行李，覆盖并排装载、旋转与边界接触、
 * 越界/重叠/缺位置/缺尺寸等 422 分支、极端坐标防溢出、封舱冻结布局以及旧航段兼容。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageFloorLayoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void loadWithFloor_sideBySideFitsAndSealFreezesLayout() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 10).andExpect(status().isCreated())
                .andExpect(jsonPath("$.floorLengthMm").value(10))
                .andExpect(jsonPath("$.floorWidthMm").value(10));
        registerBag("BAG1", 5, 10).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lengthMm").value(5))
                .andExpect(jsonPath("$.widthMm").value(10));
        registerBag("BAG2", 5, 10).andExpect(status().isCreated());

        // 两件 5x10 左右并排：(0,0) 与 (5,0)，边界接触合法
        load("LEG1", 1, List.of("BAG1", "BAG2"),
                List.of(placement("BAG1", 0, 0, 0), placement("BAG2", 5, 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.loaded", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.placements", hasSize(2)))
                .andExpect(jsonPath("$.placements[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.placements[0].xMm").value(0))
                .andExpect(jsonPath("$.placements[1].bagTag").value("BAG2"))
                .andExpect(jsonPath("$.placements[1].xMm").value(5));

        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.placements", hasSize(2)))
                .andExpect(jsonPath("$.placements[1].lengthMm").value(5))
                .andExpect(jsonPath("$.placements[1].widthMm").value(10))
                .andExpect(jsonPath("$.placements[1].rotation").value(0))
                .andExpect(jsonPath("$.placements[1].xMm").value(5))
                .andExpect(jsonPath("$.placements[1].yMm").value(0));

        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements", hasSize(2)));

        arrive("LEG1", List.of("BAG2", "BAG1")).andExpect(status().isOk());
        // 到达后冻结布局身份仍可查，不被后续状态改写
        mockMvc.perform(get("/api/legs/LEG1/difference"))
                .andExpect(jsonPath("$.arrivalType").value("EXACT"))
                .andExpect(jsonPath("$.placements", hasSize(2)))
                .andExpect(jsonPath("$.placements[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.placements[0].xMm").value(0));
    }

    @Test
    void loadWithFloor_rotationAndBoundaryContactAreLegal() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 10).andExpect(status().isCreated());
        registerBag("BAG1", 5, 10).andExpect(status().isCreated());
        registerBag("BAG2", 5, 10).andExpect(status().isCreated());

        // 旋转 90 度后每件占 10x5：上下叠放，y=5 边界接触合法
        load("LEG1", 1, List.of("BAG1", "BAG2"),
                List.of(placement("BAG1", 0, 0, 90), placement("BAG2", 0, 5, 90)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements[0].rotation").value(90))
                .andExpect(jsonPath("$.placements[1].yMm").value(5));

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(records).isEqualTo(2);
    }
    @Test
    void loadWithFloor_rejectsInvalidPlacementsAndKeepsEverythingUnchanged() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 10).andExpect(status().isCreated());
        registerBag("BAG1", 5, 10).andExpect(status().isCreated());
        registerBag("BAG2", 5, 10).andExpect(status().isCreated());

        // 布局航段未提交位置 -> 422
        load("LEG1", 1, List.of("BAG1"), null).andExpect(status().isUnprocessableEntity());
        // 位置数量与袋号数量不一致 -> 422
        load("LEG1", 1, List.of("BAG1", "BAG2"), List.of(placement("BAG1", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 位置袋号与装载袋号集合不一致 -> 422
        load("LEG1", 1, List.of("BAG1"),
                List.of(placement("BAG_UNKNOWN", 0, 0, 0))).andExpect(status().isUnprocessableEntity());
        // 批内重叠 -> 422
        load("LEG1", 1, List.of("BAG1", "BAG2"),
                List.of(placement("BAG1", 0, 0, 0), placement("BAG2", 4, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 越界：x + 5 > 10 -> 422
        load("LEG1", 1, List.of("BAG1"), List.of(placement("BAG1", 6, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 旋转角度仅支持 0/90 -> 422
        load("LEG1", 1, List.of("BAG1"), List.of(placement("BAG1", 0, 0, 45)))
                .andExpect(status().isUnprocessableEntity());
        // 负坐标 -> 422
        load("LEG1", 1, List.of("BAG1"), List.of(placement("BAG1", -1, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 极端坐标不得溢出成 500，按越界 422 处理
        load("LEG1", 1, List.of("BAG1"), List.of(placement("BAG1", Integer.MAX_VALUE, 0, 0)))
                .andExpect(status().isUnprocessableEntity());

        // 全部失败：无装载记录，航段版本不变
        Integer records = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void loadWithFloor_rejectsBagWithoutDimensionsAndOverlapWithLoaded() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 10, 10).andExpect(status().isCreated());
        registerBag("BAG1", 5, 10).andExpect(status().isCreated());
        // 未登记尺寸的行李不能装入布局航段
        registerBag("BAG_NODIM", null, null).andExpect(status().isCreated());
        load("LEG1", 1, List.of("BAG_NODIM"), List.of(placement("BAG_NODIM", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());

        load("LEG1", 1, List.of("BAG1"), List.of(placement("BAG1", 0, 0, 0)))
                .andExpect(status().isOk());
        // 与舱内已装行李重叠 -> 422
        registerBag("BAG2", 5, 10).andExpect(status().isCreated());
        load("LEG1", 2, List.of("BAG2"), List.of(placement("BAG2", 3, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 紧邻已装行李合法
        load("LEG1", 2, List.of("BAG2"), List.of(placement("BAG2", 5, 0, 0)))
                .andExpect(status().isOk());
    }

    @Test
    void legacyLegWithoutFloor_keepsOldBehaviorAndRejectsPlacements() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.floorLengthMm").doesNotExist());
        registerBag("BAG1", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lengthMm").doesNotExist());

        // 旧航段装载不要求位置
        load("LEG1", 1, List.of("BAG1"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.placements", hasSize(0)));
        // 旧航段不得提交位置
        registerBag("BAG2", 5, 10).andExpect(status().isCreated());
        load("LEG1", 2, List.of("BAG2"), List.of(placement("BAG2", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());

        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG1")))
                .andExpect(jsonPath("$.placements", hasSize(0)));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.placements", hasSize(0)));
        arrive("LEG1", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG1/difference"))
                .andExpect(jsonPath("$.arrivalType").value("EXACT"))
                .andExpect(jsonPath("$.placements", hasSize(0)));
    }

    @Test
    void register_rejectsInvalidDimensionPairs() throws Exception {
        // 舱底长宽必须同时提供且为正整数
        registerLeg("LEG_A", "PEK", "SHA", 10, null).andExpect(status().isUnprocessableEntity());
        registerLeg("LEG_B", "PEK", "SHA", 0, 10).andExpect(status().isUnprocessableEntity());
        registerLeg("LEG_C", "PEK", "SHA", -3, 10).andExpect(status().isUnprocessableEntity());

        registerLeg("LEG1", "PEK", "SHA", 10, 10).andExpect(status().isCreated());
        // 行李长宽必须同时提供且为正整数
        registerBag("BAG_A", 5, null).andExpect(status().isUnprocessableEntity());
        registerBag("BAG_B", 0, 10).andExpect(status().isUnprocessableEntity());
        registerBag("BAG_C", 5, -1).andExpect(status().isUnprocessableEntity());
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      Integer floorLengthMm, Integer floorWidthMm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        if (floorLengthMm != null) {
            body.put("floorLengthMm", floorLengthMm);
        }
        if (floorWidthMm != null) {
            body.put("floorWidthMm", floorWidthMm);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, Integer lengthMm, Integer widthMm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", List.of("LEG1"));
        if (lengthMm != null) {
            body.put("lengthMm", lengthMm);
        }
        if (widthMm != null) {
            body.put("widthMm", widthMm);
        }
        return postJson("/api/bags", body);
    }

    private Map<String, Object> placement(String bagTag, int xMm, int yMm, int rotation) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("bagTag", bagTag);
        placement.put("xMm", xMm);
        placement.put("yMm", yMm);
        placement.put("rotation", rotation);
        return placement;
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags,
                               List<Map<String, Object>> placements) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        if (placements != null) {
            body.put("placements", placements);
        }
        return postJson("/api/legs/" + legId + "/load", body);
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
