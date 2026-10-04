package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 编排版本链路测试：冻结校验（真实H2写入）、草稿绑定拒绝与版本差异。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CueVersionFlowTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cue_failure");
        jdbc.update("DELETE FROM cue_success");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    private long createVersion(String play, List<Map<String, Object>> cues) {
        ResponseEntity<JsonNode> resp = rest.postForEntity(
                "/api/plays/{play}/cue-versions", Map.of("cues", cues), JsonNode.class, play);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().get("id").asLong();
    }

    private static Map<String, Object> cue(int seq, int duration) {
        return Map.of("seqNo", seq, "cueType", "LIGHT",
                "triggerPhrase", "口令" + seq, "durationSeconds", duration);
    }

    @Test
    void freezeRejectsInvalidDurationAndKeepsDraft() {
        long id = createVersion("P1", List.of(cue(1, 30), cue(2, 0), cue(3, 601)));

        ResponseEntity<JsonNode> resp = rest.postForEntity("/api/cue-versions/{id}/freeze", null, JsonNode.class, id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("error").asText()).contains("0").contains("601");
        assertThat(jdbc.queryForObject("SELECT status FROM cue_version WHERE id = ?", String.class, id))
                .isEqualTo("DRAFT");
        // 坏提示点不被丢弃，仍保留在草稿中
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_item WHERE version_id = ?", Integer.class, id))
                .isEqualTo(3);
    }

    @Test
    void freezeRejectsDuplicateSeq() {
        long id = createVersion("P1", List.of(cue(1, 30), cue(1, 40)));

        ResponseEntity<JsonNode> resp = rest.postForEntity("/api/cue-versions/{id}/freeze", null, JsonNode.class, id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resp.getBody().get("error").asText()).contains("序号重复");
        assertThat(jdbc.queryForObject("SELECT status FROM cue_version WHERE id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }

    @Test
    void freezeValidVersionAndRejectDraftBinding() {
        long draftId = createVersion("P1", List.of(cue(1, 30)));
        ResponseEntity<JsonNode> bindDraft = rest.postForEntity("/api/sessions",
                Map.of("sessionName", "场次A", "versionId", draftId), JsonNode.class);
        assertThat(bindDraft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> frozen = rest.postForEntity(
                "/api/cue-versions/{id}/freeze", null, JsonNode.class, draftId);
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(frozen.getBody().get("status").asText()).isEqualTo("FROZEN");
        assertThat(frozen.getBody().get("frozenAt").isTextual()).isTrue();
    }

    @Test
    void diffReportsAddedRemovedAndModified() {
        long v1 = createVersion("P1", List.of(cue(1, 30), cue(2, 45), cue(3, 20)));
        long v2 = createVersion("P1", List.of(cue(1, 30), cue(2, 50), cue(4, 15)));

        ResponseEntity<JsonNode> resp = rest.getForEntity(
                "/api/cue-versions/diff?from={from}&to={to}", JsonNode.class, v1, v2);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body.get("added")).hasSize(1);
        assertThat(body.get("added").get(0).get("seqNo").asInt()).isEqualTo(4);
        assertThat(body.get("removed")).hasSize(1);
        assertThat(body.get("removed").get(0).get("seqNo").asInt()).isEqualTo(3);
        assertThat(body.get("modified")).hasSize(1);
        assertThat(body.get("modified").get(0).get("seqNo").asInt()).isEqualTo(2);
        assertThat(body.get("modified").get(0).get("from").get("durationSeconds").asInt()).isEqualTo(45);
        assertThat(body.get("modified").get(0).get("to").get("durationSeconds").asInt()).isEqualTo(50);
    }
}