package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.service.PackageService;
import com.example.starter.service.SampleService;
import com.example.starter.service.StratumService;
import com.example.starter.web.dto.Requests.CollectSampleRequest;
import com.example.starter.web.dto.Requests.CreatePackageRequest;
import com.example.starter.web.dto.Requests.RegisterStratumRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 封存交接流程测试：真实 H2 库，覆盖 20 件上限整包 422、跨层位混包、
 * 未采集入包、重复占用、封签异常停止交接、重新封存引用旧异常与交接幂等。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_package;MODE=MySQL")
@AutoConfigureMockMvc
class PackageFlowApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StratumService stratumService;

    @Autowired
    private SampleService sampleService;

    @Autowired
    private PackageService packageService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM package_item");
        jdbc.update("DELETE FROM seal_event");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM seal_package");
        jdbc.update("DELETE FROM stratum");
        stratumService.register(new RegisterStratumRequest("SITE-L", "L", "合成层位 L"));
        stratumService.register(new RegisterStratumRequest("SITE-L", "L2", "合成层位 L2"));
    }

    @Test
    void twentyItemsHandoverOkAndTwentyFirstRejectedWholePackage() throws Exception {
        for (int i = 1; i <= 21; i++) {
            collect("SMP-L-%02d".formatted(i), "L");
        }
        String packageNo = createPackage();
        for (int i = 1; i <= 20; i++) {
            addItem(packageNo, "SMP-L-%02d".formatted(i)).andExpect(status().isOk());
        }
        addItem(packageNo, "SMP-L-21")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PACKAGE_FULL"));
        mvc.perform(get("/api/packages/{no}", packageNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items", hasSize(20)));
        handover(packageNo, "req-h-20", "SEAL-L-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.sealNo").value("SEAL-L-001"))
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.sealEvents[0].eventType").value("HANDED_OVER"));
        Integer handedOver = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample WHERE status = 'HANDED_OVER'", Integer.class);
        assertEquals(20, handedOver, "整包 20 件必须原子交接");
        mvc.perform(get("/api/samples/SMP-L-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stratumNo").value("L"))
                .andExpect(jsonPath("$.packageNo").value(packageNo))
                .andExpect(jsonPath("$.packageVersion").isNumber())
                .andExpect(jsonPath("$.sealEvents[0].eventType").value("HANDED_OVER"));
    }

    @Test
    void crossStratumAndUncollectedSampleRejected() throws Exception {
        collect("SMP-X-1", "L2");
        String packageNo = createPackage();
        addItem(packageNo, "SMP-X-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CROSS_STRATUM"));
        addItem(packageNo, "SMP-NEVER-COLLECTED")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAMPLE_NOT_COLLECTED"));
        mvc.perform(get("/api/packages/{no}", packageNo))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void duplicateSampleAssignmentConflict() throws Exception {
        collect("SMP-D-1", "L");
        String p1 = createPackage();
        String p2 = createPackage();
        addItem(p1, "SMP-D-1").andExpect(status().isOk());
        addItem(p2, "SMP-D-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAMPLE_ALREADY_ASSIGNED"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_item WHERE sample_id = "
                        + "(SELECT id FROM sample WHERE sample_no = 'SMP-D-1')", Integer.class);
        assertEquals(1, count, "样本只能归属一个包");
    }
    @Test
    void damageStopsHandoverAndResealCreatesLinkedPackage() throws Exception {
        collect("SMP-M-1", "L");
        collect("SMP-M-2", "L");
        String packageNo = createPackage();
        addItem(packageNo, "SMP-M-1").andExpect(status().isOk());
        addItem(packageNo, "SMP-M-2").andExpect(status().isOk());
        mvc.perform(post("/api/packages/{no}/damage", packageNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"封签破损，编号签条撕裂\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABNORMAL"))
                .andExpect(jsonPath("$.sealEvents[0].eventType").value("DAMAGED"));
        handover(packageNo, "req-h-dmg", "SEAL-X")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_HANDOVERABLE"));
        addItem(packageNo, "SMP-M-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PACKAGE_NOT_OPEN"));
        Integer stillInPackage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample WHERE status = 'IN_PACKAGE'", Integer.class);
        assertEquals(2, stillInPackage, "失败交接不得改变样本状态");

        String resealBody = mvc.perform(post("/api/packages/{no}/reseal", packageNo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.sealEvents[0].eventType").value("RESEAL_CREATED"))
                .andReturn().getResponse().getContentAsString();
        String newPackageNo = com.jayway.jsonpath.JsonPath.read(resealBody, "$.packageNo");
        Number sourceEventId = com.jayway.jsonpath.JsonPath.read(resealBody, "$.sourceEventId");
        Number oldDamageEventId = jdbc.queryForObject(
                "SELECT id FROM seal_event WHERE event_type = 'DAMAGED'", Number.class);
        assertEquals(oldDamageEventId.longValue(), sourceEventId.longValue(),
                "新包必须引用旧包异常记录");
        mvc.perform(get("/api/packages/{no}", packageNo))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.sealEvents[?(@.eventType=='DAMAGED')]").exists())
                .andExpect(jsonPath("$.sealEvents[?(@.eventType=='SUPERSEDED')]").exists());
        mvc.perform(get("/api/samples/SMP-M-1"))
                .andExpect(jsonPath("$.packageNo").value(newPackageNo));
        handover(newPackageNo, "req-h-new", "SEAL-NEW-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"));
        mvc.perform(post("/api/packages/{no}/damage", newPackageNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"已交接后再报损\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_ALREADY_HANDED_OVER"));
    }

    @Test
    void handoverIdempotencyAndFailureKeepsRequestKey() throws Exception {
        collect("SMP-I-1", "L");
        String packageNo = createPackage();
        addItem(packageNo, "SMP-I-1").andExpect(status().isOk());
        String first = handover(packageNo, "req-h-1", "SEAL-I-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = handover(packageNo, "req-h-1", "SEAL-I-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "同参重放必须返回首次响应");
        handover(packageNo, "req-h-1", "SEAL-OTHER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        String emptyPkg = createPackage();
        handover(emptyPkg, "req-h-empty", "SEAL-E")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PACKAGE_EMPTY"));
        collect("SMP-I-2", "L");
        addItem(emptyPkg, "SMP-I-2").andExpect(status().isOk());
        handover(emptyPkg, "req-h-empty", "SEAL-E")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"));
    }

    private void collect(String sampleNo, String stratumNo) {
        sampleService.collect(new CollectSampleRequest(
                "req-collect-" + sampleNo, sampleNo, "SITE-L", stratumNo, "G-01"));
    }

    private String createPackage() {
        return packageService.create(new CreatePackageRequest("SITE-L", "L")).packageNo();
    }

    private ResultActions addItem(String packageNo, String sampleNo) throws Exception {
        return mvc.perform(post("/api/packages/{no}/items", packageNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sampleNo\":\"%s\"}".formatted(sampleNo)));
    }

    private ResultActions handover(String packageNo, String requestId, String sealNo) throws Exception {
        return mvc.perform(post("/api/packages/{no}/handover", packageNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"%s\",\"sealNo\":\"%s\"}".formatted(requestId, sealNo)));
    }
}