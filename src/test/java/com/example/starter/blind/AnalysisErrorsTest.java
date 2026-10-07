package com.example.starter.blind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 受限分析错误分支与幂等：未关闭 409；人数/重复/退组/未登记/缺值 422；版本不符 409；
 * 组人数不足 422；换序同参重放、异参 409、失败不占键；权限先于重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisErrorsTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void createExperiment(String exp, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String exp, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private void submit(String exp, String pid, int value, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/results",
                headers("c1", "COORDINATOR", requestId), "{\"value\":" + value + "}")
                .getStatusCode().value());
    }

    private void close(String exp, String requestId) {
        assertEquals(200, post("/api/experiments/" + exp + "/close",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> analyze(String exp, String idsJson, int version,
                                           String actor, String requestId) {
        return post("/api/experiments/" + exp + "/analyses",
                headers(actor, "REVIEWER", requestId),
                "{\"participantIds\":" + idsJson + ",\"resultVersion\":" + version + "}");
    }

    /** 建实验、登记 P1~P4（P1/P2=A，P3/P4=B）、各交结果并关闭。 */
    private void setupClosedWithResults(String exp, String prefix) {
        createExperiment(exp, prefix + "-create");
        for (int i = 1; i <= 4; i++) {
            register(exp, "P" + i, prefix + "-alloc-" + i);
            submit(exp, "P" + i, i * 10, prefix + "-sub-" + i);
        }
        close(exp, prefix + "-close");
    }

    @Test
    void analyze_beforeClose_andInvalidVersion_rejected() {
        createExperiment("ERR-1", "err1-create");
        for (int i = 1; i <= 4; i++) {
            register("ERR-1", "P" + i, "err1-alloc-" + i);
            submit("ERR-1", "P" + i, i * 10, "err1-sub-" + i);
        }
        // 未关闭：409
        assertEquals(409, analyze("ERR-1", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "r1", "err1-an-open")
                .getStatusCode().value());
        close("ERR-1", "err1-close");
        // 版本号小于 1：400（请求校验）
        assertEquals(400, analyze("ERR-1", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 0, "r1", "err1-an-v0")
                .getStatusCode().value());
        // 版本超出已有版本：409
        assertEquals(409, analyze("ERR-1", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 2, "r1", "err1-an-v2")
                .getStatusCode().value());
        // 失败均不留统计
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'ERR-1'", Integer.class));
    }

    @Test
    void analyze_referenceCountDuplicateWithdrawnUnknownMissing_rejected() {
        setupClosedWithResults("ERR-2", "err2");
        // 人数不足 4：422
        assertEquals(422, analyze("ERR-2", "[\"P1\",\"P2\",\"P3\"]", 1, "r1", "err2-an-few")
                .getStatusCode().value());
        // 人数超过 8：422
        assertEquals(422, analyze("ERR-2",
                "[\"P1\",\"P2\",\"P3\",\"P4\",\"P5\",\"P6\",\"P7\",\"P8\",\"P9\"]", 1, "r1", "err2-an-many")
                .getStatusCode().value());
        // 重复引用：422
        assertEquals(422, analyze("ERR-2", "[\"P1\",\"P1\",\"P2\",\"P3\"]", 1, "r1", "err2-an-dup")
                .getStatusCode().value());
        // 未登记参与者：422
        assertEquals(422, analyze("ERR-2", "[\"P1\",\"P2\",\"P3\",\"P9\"]", 1, "r1", "err2-an-unknown")
                .getStatusCode().value());
        // 全部失败不留统计、不占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'ERR-2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id LIKE 'err2-an-%'",
                Integer.class));
    }

    @Test
    void analyze_withdrawnMissingValueAndGroupMinimum_rejected() {
        // 退组参与者：ERR-3 引用已退组的 P4 → 422
        createExperiment("ERR-3", "err3-create");
        for (int i = 1; i <= 4; i++) {
            register("ERR-3", "P" + i, "err3-alloc-" + i);
            submit("ERR-3", "P" + i, i * 10, "err3-sub-" + i);
        }
        assertEquals(200, post("/api/experiments/ERR-3/participants/P4/withdrawal",
                headers("c1", "COORDINATOR", "err3-withdraw"), null).getStatusCode().value());
        close("ERR-3", "err3-close");
        assertEquals(422, analyze("ERR-3", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "r1", "err3-an")
                .getStatusCode().value());

        // 缺值：ERR-4 的 P4 未提交结果 → 422
        createExperiment("ERR-4", "err4-create");
        for (int i = 1; i <= 4; i++) {
            register("ERR-4", "P" + i, "err4-alloc-" + i);
        }
        submit("ERR-4", "P1", 10, "err4-sub-1");
        submit("ERR-4", "P2", 20, "err4-sub-2");
        submit("ERR-4", "P3", 30, "err4-sub-3");
        close("ERR-4", "err4-close");
        assertEquals(422, analyze("ERR-4", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "r1", "err4-an")
                .getStatusCode().value());

        // 组人数不足：ERR-5 引用 4 个 A 席（P1、P2、P5、P6）→ B 组 0 人，422
        createExperiment("ERR-5", "err5-create");
        for (int i = 1; i <= 6; i++) {
            register("ERR-5", "P" + i, "err5-alloc-" + i);
            submit("ERR-5", "P" + i, i * 10, "err5-sub-" + i);
        }
        close("ERR-5", "err5-close");
        assertEquals(422, analyze("ERR-5", "[\"P1\",\"P2\",\"P5\",\"P6\"]", 1, "r1", "err5-an")
                .getStatusCode().value());

        // 全部失败不留统计
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id IN ('ERR-3','ERR-4','ERR-5')",
                Integer.class));
    }

    @Test
    void analyze_idempotency_reorderSameParamsReplay_andPermissionPrecedesReplay() throws Exception {
        setupClosedWithResults("ERR-6", "err6");
        ResponseEntity<String> first = analyze("ERR-6", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "r1", "err6-key");
        assertEquals(201, first.getStatusCode().value());
        // 换序同参重放：返回首次完整响应，不产生第二条统计
        ResponseEntity<String> replay = analyze("ERR-6", "[\"P4\",\"P3\",\"P2\",\"P1\"]", 1, "r1", "err6-key");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'ERR-6'", Integer.class));
        // 同键异参（不同版本）：409
        assertEquals(409, analyze("ERR-6", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 2, "r1", "err6-key")
                .getStatusCode().value());
        // 权限先于重放：COORDINATOR 持同键同参 → 403 而非回放
        assertEquals(403, post("/api/experiments/ERR-6/analyses",
                headers("c1", "COORDINATOR", "err6-key"),
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"resultVersion\":1}")
                .getStatusCode().value());
        // 失败不占键：缺值场景 422 后，同键换合法参数成功
        createExperiment("ERR-7", "err7-create");
        for (int i = 1; i <= 4; i++) {
            register("ERR-7", "P" + i, "err7-alloc-" + i);
        }
        submit("ERR-7", "P1", 10, "err7-sub-1");
        submit("ERR-7", "P2", 20, "err7-sub-2");
        submit("ERR-7", "P3", 30, "err7-sub-3");
        submit("ERR-7", "P4", 40, "err7-sub-4");
        close("ERR-7", "err7-close");
        assertEquals(422, analyze("ERR-7", "[\"P1\",\"P2\",\"P3\",\"P9\"]", 1, "r1", "err7-key")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'err7-key'",
                Integer.class));
        assertEquals(201, analyze("ERR-7", "[\"P1\",\"P2\",\"P3\",\"P4\"]", 1, "r1", "err7-key")
                .getStatusCode().value());
    }
}
