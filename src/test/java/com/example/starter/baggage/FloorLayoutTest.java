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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.PositionRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平面舱位布局 API 测试（真实 H2 MySQL 模式）：合成 10x10 舱底、两件 5x10 行李
 * 左右并排装载、整批互换、越界/重叠回滚、封舱冻结与无二维配置旧航段兼容、幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FloorLayoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_size");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM floor_layout");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void load_sideBySidePlacementThenSealFreezesFullIdentity() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");

        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.placements", hasSize(2)))
                .andExpect(jsonPath("$.placements[0].bagTag").value("BA"))
                .andExpect(jsonPath("$.placements[0].x").value(0))
                .andExpect(jsonPath("$.placements[1].bagTag").value("BB"))
                .andExpect(jsonPath("$.placements[1].x").value(5));

        mockMvc.perform(get("/api/legs/F1/layout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorWidthMm").value(10))
                .andExpect(jsonPath("$.floorHeightMm").value(10))
                .andExpect(jsonPath("$.placements", hasSize(2)));

        seal("F1", 2, "alice").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", contains("BA", "BB")))
                .andExpect(jsonPath("$.placements", contains(
                        org.hamcrest.Matchers.hasEntry("bagTag", "BA"),
                        org.hamcrest.Matchers.hasEntry("bagTag", "BB"))))
                .andExpect(jsonPath("$.placements[1].widthMm").value(5))
                .andExpect(jsonPath("$.placements[1].heightMm").value(10))
                .andExpect(jsonPath("$.placements[1].rotated").value(false))
                .andExpect(jsonPath("$.placements[1].x").value(5));

        // 封舱后布局查询返回冻结快照而非当前表数据
        mockMvc.perform(get("/api/legs/F1/layout"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.placements[0].x").value(0));
        mockMvc.perform(get("/api/legs/F1/manifest"))
                .andExpect(jsonPath("$.floorWidthMm").value(10))
                .andExpect(jsonPath("$.placements", hasSize(2)));

        // 精确到达沿用冻结袋号清单；到达后冻结身份仍可查
        arriveExact("F1", List.of("BB", "BA"), "alice").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/legs/F1/manifest"))
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.manifest", contains("BA", "BB")))
                .andExpect(jsonPath("$.placements[1].x").value(5));
    }

    @Test
    void load_rejectsOutOfBoundsOverlapAndMissingPositionWithFullRollback() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        registerBagNoSize("BN", "F1");

        // 未随附落位 -> 422
        postJson("/api/legs/F1/load", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("BA"), "operator", "alice"))
                .andExpect(status().isUnprocessableEntity());
        // 落位与袋号不对应 -> 422
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                Map.of("bagTag", "BX", "rotated", false, "x", 5, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 越界：x=6 时右边界 11 > 10 -> 422
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 6, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 同批内部重叠（侵入 1 毫米）-> 422
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                Map.of("bagTag", "BB", "rotated", false, "x", 4, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 未登记尺寸的行李不能进入平面舱位 -> 422
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BN", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());

        // 任何失败后均无落库、版本不变
        assertThat(count("SELECT COUNT(*) FROM load_record")).isZero();
        mockMvc.perform(get("/api/legs/F1/layout"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.placements", hasSize(0)));

        // 先合法装入 BA，再尝试与在位行李重叠的整批：整批 422 且 BA 位置不变
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA"), List.of(new PositionRequest("BA", false, 0, 0)), "alice"));
        loadWithPositions("F1", 2, List.of(
                Map.of("bagTag", "BB", "rotated", false, "x", 4, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // BB 贴 BA 右边界 (5,0) 落位即合法
        loadWithPositions("F1", 2, List.of(
                Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        Integer baX = jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BA'", Integer.class);
        assertThat(baX).isZero();
    }

    @Test
    void rearrange_batchSwapIsLegalAndSingleMoveIntoOccupiedSpotRejects() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA", "BB"),
                List.of(new PositionRequest("BA", false, 0, 0),
                        new PositionRequest("BB", false, 5, 0)), "alice"));

        // 单独把右件 BB 搬到仍被 BA 占用的左位 -> 422，两件位置与版本均不变
        rearrange("F1", 2, List.of(
                Map.of("bagTag", "BB", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        Map<String, Object> baAfter = jdbcTemplate.queryForMap(
                "SELECT pos_x, pos_y FROM load_record WHERE bag_tag = 'BA'");
        Map<String, Object> bbAfter = jdbcTemplate.queryForMap(
                "SELECT pos_x, pos_y FROM load_record WHERE bag_tag = 'BB'");
        assertThat(baAfter.get("pos_x")).isEqualTo(0);
        assertThat(bbAfter.get("pos_x")).isEqualTo(5);
        mockMvc.perform(get("/api/legs/F1/layout")).andExpect(jsonPath("$.version").value(2));

        // 整批交换左右位置合法：先整体移除旧位再统一校验，不发生暂时冲突
        rearrange("F1", 2, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 5, "y", 0),
                Map.of("bagTag", "BB", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.placements", hasSize(2)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BA'", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pos_x FROM load_record WHERE bag_tag = 'BB'", Integer.class)).isEqualTo(0);

        // 封舱后禁止重排
        seal("F1", 3, "alice").andExpect(status().isOk());
        rearrange("F1", 4, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rearrange_validatesMovedSetAndKeepsVersionOnAnyViolation() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        registerSizedBag("BC", 4, 4, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA", "BB"),
                List.of(new PositionRequest("BA", false, 0, 0),
                        new PositionRequest("BB", false, 5, 0)), "alice"));

        // 搬动未装载在本航段的行李 -> 422
        rearrange("F1", 2, List.of(
                Map.of("bagTag", "BC", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 新位置越界 -> 422，版本不变
        rearrange("F1", 2, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 6, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 重排袋号重复 -> 422
        rearrange("F1", 2, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 1, "y", 0),
                Map.of("bagTag", "BA", "rotated", false, "x", 2, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        // 版本不符 -> 409
        rearrange("F1", 99, List.of(
                Map.of("bagTag", "BA", "rotated", false, "x", 1, "y", 0)), "alice")
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/legs/F1/layout"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.placements[0].x").value(0));
    }

    @Test
    void load_supportsRotationAndLegacyLegsRemainPositionFree() throws Exception {
        // 8x4 的行李在 4x8 舱底：不旋转（8x4）x 轴越界，旋转 90 度（4x8）贴边合法
        registerFloorLeg("F1", 4, 8);
        registerSizedBag("BR", 8, 4, "F1");
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BR", "rotated", false, "x", 0, "y", 0)), "alice")
                .andExpect(status().isUnprocessableEntity());
        loadWithPositions("F1", 1, List.of(
                Map.of("bagTag", "BR", "rotated", true, "x", 0, "y", 0)), "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements[0].rotated").value(true))
                .andExpect(jsonPath("$.placements[0].x").value(0));
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT width_mm, height_mm, rotated, pos_x, pos_y FROM load_record WHERE bag_tag = 'BR'");
        assertThat(stored.get("width_mm")).isEqualTo(8);
        assertThat(stored.get("height_mm")).isEqualTo(4);
        assertThat(stored.get("rotated")).isEqualTo(true);

        // 无二维配置旧航段：不带位置照常装载/封舱/到达，布局查询 422
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "OLD", "origin", "CAN", "destination", "XIY", "operator", "setup"))
                .andExpect(status().isCreated());
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                "BOLD", null, null, List.of("OLD"), "setup"));
        postJson("/api/legs/OLD/load", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "bagTags", List.of("BOLD"), "operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements").doesNotExist());
        seal("OLD", 2, "alice").andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BOLD")))
                .andExpect(jsonPath("$.placements").doesNotExist());
        mockMvc.perform(get("/api/legs/OLD/layout")).andExpect(status().isUnprocessableEntity());
        arriveExact("OLD", List.of("BOLD"), "alice").andExpect(status().isOk());
    }

    @Test
    void idempotency_loadAndRearrangeReplayAndDifferencesReturn409() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");

        String loadId = UUID.randomUUID().toString();
        Map<String, Object> loadBody = Map.of("requestId", loadId, "expectedVersion", 1,
                "bagTags", List.of("BA", "BB"),
                "positions", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)),
                "operator", "alice");
        postJson("/api/legs/F1/load", loadBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同参回放（positions 换序）仍重放首次完整结果，版本不再推进
        postJson("/api/legs/F1/load", Map.of("requestId", loadId, "expectedVersion", 1,
                "bagTags", List.of("BB", "BA"),
                "positions", List.of(
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0),
                        Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 同键改坐标 -> 409
        postJson("/api/legs/F1/load", Map.of("requestId", loadId, "expectedVersion", 1,
                "bagTags", List.of("BA", "BB"),
                "positions", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 1, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isConflict());
        // 同键改操作者（文本原样比较）-> 409
        postJson("/api/legs/F1/load", Map.of("requestId", loadId, "expectedVersion", 1,
                "bagTags", List.of("BA", "BB"),
                "positions", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)),
                "operator", "bob"))
                .andExpect(status().isConflict());
        // 同键改期望版本 -> 409
        postJson("/api/legs/F1/load", Map.of("requestId", loadId, "expectedVersion", 2,
                "bagTags", List.of("BA", "BB"),
                "positions", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isConflict());

        // 重排同参重放，改参 409；失败不占键
        String rearrangeId = UUID.randomUUID().toString();
        Map<String, Object> swap = Map.of("requestId", rearrangeId, "expectedVersion", 2,
                "moves", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 5, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 0, "y", 0)),
                "operator", "alice");
        postJson("/api/legs/F1/rearrange", swap).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        postJson("/api/legs/F1/rearrange", swap).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        postJson("/api/legs/F1/rearrange", Map.of("requestId", rearrangeId, "expectedVersion", 2,
                "moves", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 0, "y", 0),
                        Map.of("bagTag", "BB", "rotated", false, "x", 5, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isConflict());

        // 先以越界移动失败（422）不占键，同键修正为合法位置后成功
        String retryId = UUID.randomUUID().toString();
        postJson("/api/legs/F1/rearrange", Map.of("requestId", retryId, "expectedVersion", 3,
                "moves", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 9, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/legs/F1/rearrange", Map.of("requestId", retryId, "expectedVersion", 3,
                "moves", List.of(
                        Map.of("bagTag", "BA", "rotated", false, "x", 5, "y", 0)),
                "operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, retryId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void extremeDimensions_persistOnH2AndBoundsUseLongArithmetic() {
        // 10 亿毫米舱底与行李真实落 H2 INT 列；x+宽按 long 比较，不发生 int 溢出误判
        baggageService.registerLeg(new RegisterLegRequest(UUID.randomUUID().toString(),
                "BIG", "PEK", "SHA", 1_000_000_000, 1_000_000_000, "setup"));
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                "HUGE", 1_000_000_000, 1_000_000_000, List.of("BIG"), "setup"));

        // 放在 (1,0)：右边界 1_000_000_001 > 1_000_000_000 -> 422（int 相加不会溢出成负数误放）
        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> baggageService.load("BIG",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("HUGE"),
                        List.of(new PositionRequest("HUGE", false, 1, 0)), "alice")));
        // 贴原点恰好放下
        baggageService.load("BIG", new LoadRequest(UUID.randomUUID().toString(), 1, List.of("HUGE"),
                List.of(new PositionRequest("HUGE", false, 0, 0)), "alice"));
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT width_mm, height_mm, pos_x, pos_y FROM load_record WHERE bag_tag = 'HUGE'");
        assertThat(stored.get("width_mm")).isEqualTo(1_000_000_000);
        assertThat(stored.get("pos_x")).isEqualTo(0);
    }

    @Test
    void differenceArriveAndRecover_keepFrozenLayoutIdentity() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        registerSizedBag("BB", 5, 10, "F1");
        baggageService.load("F1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BA", "BB"),
                List.of(new PositionRequest("BA", false, 0, 0),
                        new PositionRequest("BB", false, 5, 0)), "alice"));
        seal("F1", 2, "alice").andExpect(status().isOk());
        String frozenBefore = jdbcTemplate.queryForObject(
                "SELECT sealed_layout FROM leg WHERE leg_id = 'F1'", String.class);

        // 差异短卸 BB：冻结布局快照不被后续状态改写
        postJson("/api/legs/F1/arrive-difference", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 3, "bagTags", List.of("BA"), "operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("BB")));
        String frozenAfter = jdbcTemplate.queryForObject(
                "SELECT sealed_layout FROM leg WHERE leg_id = 'F1'", String.class);
        assertThat(frozenAfter).isEqualTo(frozenBefore);
        mockMvc.perform(get("/api/legs/F1/manifest"))
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.placements[1].bagTag").value("BB"))
                .andExpect(jsonPath("$.placements[1].x").value(5));
        // 到达后 load_record 已清空，坐标只存在于冻结快照
        assertThat(count("SELECT COUNT(*) FROM load_record")).isZero();

        // 短卸补到沿用袋号身份，不依赖后来位置
        postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "BB", "missingLegId", "F1", "actualStation", "SHA", "operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void idempotency_decimalCoordinatesComparedByValue() throws Exception {
        registerFloorLeg("F1", 10, 10);
        registerSizedBag("BA", 5, 10, "F1");
        String requestId = UUID.randomUUID().toString();
        // 首次：整数坐标
        mockMvc.perform(post("/api/legs/F1/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":1,"
                                + "\"bagTags\":[\"BA\"],\"operator\":\"alice\","
                                + "\"positions\":[{\"bagTag\":\"BA\",\"rotated\":false,\"x\":0,\"y\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 重放：坐标写成 0.0（十进制按值比较），仍返回首次结果而非 409
        mockMvc.perform(post("/api/legs/F1/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":1,"
                                + "\"bagTags\":[\"BA\"],\"operator\":\"alice\","
                                + "\"positions\":[{\"bagTag\":\"BA\",\"rotated\":false,\"x\":0.0,\"y\":0.0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    // __MORE_TESTS__

    private ResultActions registerFloorLeg(String legId, int width, int height) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", "PEK", "destination", "SHA",
                "floorWidthMm", width, "floorHeightMm", height, "operator", "setup"));
    }

    private void registerSizedBag(String bagTag, int width, int height, String legId) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, width, height, List.of(legId), "setup"));
    }

    private void registerBagNoSize(String bagTag, String legId) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(),
                bagTag, null, null, List.of(legId), "setup"));
    }

    private ResultActions loadWithPositions(String legId, int expectedVersion,
                                            List<Map<String, Object>> positions, String operator)
            throws Exception {
        List<String> bagTags = positions.stream().map(p -> (String) p.get("bagTag")).toList();
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags,
                "positions", positions, "operator", operator));
    }

    private ResultActions rearrange(String legId, int expectedVersion,
                                    List<Map<String, Object>> moves, String operator) throws Exception {
        return postJson("/api/legs/" + legId + "/rearrange", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "moves", moves, "operator", operator));
    }

    private ResultActions seal(String legId, int expectedVersion, String operator) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "operator", operator));
    }

    private ResultActions arriveExact(String legId, List<String> bagTags, String operator) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags, "operator", operator));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
