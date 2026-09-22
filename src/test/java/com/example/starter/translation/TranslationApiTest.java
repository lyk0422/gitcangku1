package com.example.starter.translation;

import com.example.starter.translation.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多语种段落修订与发布快照 API 的主流程、失败分支与幂等边界测试（真实 H2 库）。
 */
class TranslationApiTest extends IntegrationTestBase {

    @Test
    void 主流程_建文档到发布并查询快照_发布后修订不改快照() throws Exception {
        // 建文档：2 种目标语言、2 个段落，草稿版本 1、发布版本 0
        createDoc("req-c1", "doc-1", List.of("en", "ja"), List.of(seg("s1", "Hello"), seg("s2", "World")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("doc-1"))
                .andExpect(jsonPath("$.draftVersion").value(1))
                .andExpect(jsonPath("$.publishedVersion").value(0))
                .andExpect(jsonPath("$.targetLanguages.length()").value(2))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));

        // 提交 4 份译文（每次草稿版本 +1，共 1+4=5）
        submitTranslation("doc-1", "s1", "req-t1", "en", "Hello EN", 1, "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translationVersion").value(1))
                .andExpect(jsonPath("$.sourceVersion").value(1))
                .andExpect(jsonPath("$.author").value("bob"));
        submitTranslation("doc-1", "s1", "req-t2", "ja", "こんにちは", 1, "bob").andExpect(status().isOk());
        submitTranslation("doc-1", "s2", "req-t3", "en", "World EN", 1, "bob").andExpect(status().isOk());
        submitTranslation("doc-1", "s2", "req-t4", "ja", "世界", 1, "bob").andExpect(status().isOk());

        // 另一审核人批准 4 份译文
        approve("doc-1", "s1", "en", "req-a1", 1, 1, "carol")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewer").value("carol"))
                .andExpect(jsonPath("$.approvedAt").value("2026-01-01T00:00:00Z"));
        approve("doc-1", "s1", "ja", "req-a2", 1, 1, "carol").andExpect(status().isOk());
        approve("doc-1", "s2", "en", "req-a3", 1, 1, "carol").andExpect(status().isOk());
        approve("doc-1", "s2", "ja", "req-a4", 1, 1, "carol").andExpect(status().isOk());

        // 发布：期望草稿 5、期望发布 0 → 发布版本 1
        publish("doc-1", "req-p1", 5, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.draftVersion").value(5))
                .andExpect(jsonPath("$.publishedAt").value("2026-01-01T00:00:00Z"));

        // 查询发布版本 1 的完整快照
        MvcResult snapshot = getJson("/api/documents/doc-1/publications/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].translations.length()").value(2))
                .andReturn();
        assertThat(body(snapshot).at("/segments/0/sourceText").asText()).isEqualTo("Hello");

        // 发布后修订源文：源文版本与草稿版本递增，但已发布快照不变
        clock.set(Instant.parse("2026-01-02T00:00:00Z"));
        reviseSource("doc-1", "s1", "req-r1", "Hello v2", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftVersion").value(6))
                .andExpect(jsonPath("$.segments[0].sourceVersion").value(2));
        getJson("/api/documents/doc-1/publications/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].sourceText").value("Hello"))
                .andExpect(jsonPath("$.segments[0].sourceVersion").value(1))
                .andExpect(jsonPath("$.publishedAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void 建文档_参数校验失败分支() throws Exception {
        // 目标语言 0 种 / 6 种 / 重复
        postJson("/api/documents", "alice", createDocBody("req-v1", "doc-v1", List.of(), List.of(seg("s1", "x"))))
                .andExpect(status().isBadRequest());
        postJson("/api/documents", "alice", createDocBody("req-v2", "doc-v2",
                List.of("a", "b", "c", "d", "e", "f"), List.of(seg("s1", "x"))))
                .andExpect(status().isBadRequest());
        postJson("/api/documents", "alice", createDocBody("req-v3", "doc-v3",
                List.of("en", "en"), List.of(seg("s1", "x"))))
                .andExpect(status().isBadRequest());
        // 无初始段落 / 段落 ID 重复
        postJson("/api/documents", "alice", createDocBody("req-v4", "doc-v4", List.of("en"), List.of()))
                .andExpect(status().isBadRequest());
        postJson("/api/documents", "alice", createDocBody("req-v5", "doc-v5", List.of("en"),
                List.of(seg("s1", "x"), seg("s1", "y"))))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id
        postJson("/api/documents", null, createDocBody("req-v6", "doc-v6", List.of("en"), List.of(seg("s1", "x"))))
                .andExpect(status().isBadRequest());
        // 失败不占键：上述 requestId 均可被合法请求复用
        createDoc("req-v1", "doc-v1", List.of("en"), List.of(seg("s1", "x")))
                .andExpect(status().isOk());
    }

    @Test
    void 建文档_文档ID冲突返回409() throws Exception {
        createDoc("req-d1", "doc-dup", List.of("en"), List.of(seg("s1", "x"))).andExpect(status().isOk());
        createDoc("req-d2", "doc-dup", List.of("en"), List.of(seg("s1", "y")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void 未找到_文档段落发布版本返回404() throws Exception {
        reviseSource("doc-none", "s1", "req-n1", "x", "alice").andExpect(status().isNotFound());
        submitTranslation("doc-none", "s1", "req-n2", "en", "x", 1, "bob").andExpect(status().isNotFound());
        publish("doc-none", "req-n3", 1, 0).andExpect(status().isNotFound());
        getJson("/api/documents/doc-none/publications/1").andExpect(status().isNotFound());

        createDoc("req-n4", "doc-nf", List.of("en"), List.of(seg("s1", "x"))).andExpect(status().isOk());
        reviseSource("doc-nf", "s-none", "req-n5", "x", "alice").andExpect(status().isNotFound());
        approve("doc-nf", "s1", "en", "req-n6", 1, 1, "carol").andExpect(status().isNotFound());
        getJson("/api/documents/doc-nf/publications/9").andExpect(status().isNotFound());
    }

    @Test
    void 译文提交_源文版本不匹配409_非目标语言422() throws Exception {
        createDoc("req-s1", "doc-s", List.of("en"), List.of(seg("s1", "Hello"))).andExpect(status().isOk());
        submitTranslation("doc-s", "s1", "req-s2", "en", "x", 99, "bob")
                .andExpect(status().isConflict());
        submitTranslation("doc-s", "s1", "req-s3", "fr", "x", 1, "bob")
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同 requestId 修正参数后成功
        submitTranslation("doc-s", "s1", "req-s2", "en", "Hello EN", 1, "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translationVersion").value(1));
        // 重新提交：译文版本递增
        submitTranslation("doc-s", "s1", "req-s4", "en", "Hello EN v2", 1, "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translationVersion").value(2));
    }

    @Test
    void 译文批准_作者不得自审_版本必须匹配_译文待更新不可批() throws Exception {
        createDoc("req-a1", "doc-a", List.of("en"), List.of(seg("s1", "Hello"))).andExpect(status().isOk());
        submitTranslation("doc-a", "s1", "req-a2", "en", "Hello EN", 1, "bob").andExpect(status().isOk());
        // 作者自审 → 422
        approve("doc-a", "s1", "en", "req-a3", 1, 1, "bob").andExpect(status().isUnprocessableEntity());
        // 版本不匹配 → 409
        approve("doc-a", "s1", "en", "req-a4", 2, 1, "carol").andExpect(status().isConflict());
        approve("doc-a", "s1", "en", "req-a5", 1, 9, "carol").andExpect(status().isConflict());
        // 源文修订后译文待更新 → 422（即使携带新的源文版本）
        reviseSource("doc-a", "s1", "req-a6", "Hello v2", "alice").andExpect(status().isOk());
        approve("doc-a", "s1", "en", "req-a7", 2, 1, "carol").andExpect(status().isUnprocessableEntity());
        // 重新提交基于源文版本 2 的译文后可批准
        submitTranslation("doc-a", "s1", "req-a8", "en", "Hello EN v2", 2, "bob").andExpect(status().isOk());
        approve("doc-a", "s1", "en", "req-a9", 2, 2, "carol")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceVersion").value(2))
                .andExpect(jsonPath("$.translationVersion").value(2));
    }

    @Test
    void 发布_缺译422_审核失效422_版本冲突409_失败不留部分快照() throws Exception {
        createDoc("req-p1", "doc-p", List.of("en", "ja"), List.of(seg("s1", "Hello"))).andExpect(status().isOk());
        // 版本冲突优先：期望草稿版本错误 → 409
        publish("doc-p", "req-p2", 99, 0).andExpect(status().isConflict());
        // 缺译 → 422
        publish("doc-p", "req-p3", 1, 0).andExpect(status().isUnprocessableEntity());

        submitTranslation("doc-p", "s1", "req-p4", "en", "Hello EN", 1, "bob").andExpect(status().isOk());
        submitTranslation("doc-p", "s1", "req-p5", "ja", "こんにちは", 1, "bob").andExpect(status().isOk());
        approve("doc-p", "s1", "en", "req-p6", 1, 1, "carol").andExpect(status().isOk());
        // ja 缺批准 → 422
        publish("doc-p", "req-p7", 3, 0).andExpect(status().isUnprocessableEntity());
        approve("doc-p", "s1", "ja", "req-p8", 1, 1, "carol").andExpect(status().isOk());

        // 源文修订使批准失效 → 422
        reviseSource("doc-p", "s1", "req-p9", "Hello v2", "alice").andExpect(status().isOk());
        publish("doc-p", "req-p10", 4, 0).andExpect(status().isUnprocessableEntity());

        // 全部失败均未产生部分快照，发布版本仍为 0
        getJson("/api/documents/doc-p/publications/1").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT published_version FROM document WHERE document_id = 'doc-p'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication", Integer.class)).isZero();

        // 重新提交并批准后可正常发布（发布版本仍是 0 → 1）
        submitTranslation("doc-p", "s1", "req-p11", "en", "Hello EN v2", 2, "bob").andExpect(status().isOk());
        submitTranslation("doc-p", "s1", "req-p12", "ja", "こんにちは v2", 2, "bob").andExpect(status().isOk());
        approve("doc-p", "s1", "en", "req-p13", 2, 2, "carol").andExpect(status().isOk());
        approve("doc-p", "s1", "ja", "req-p14", 2, 2, "carol").andExpect(status().isOk());
        publish("doc-p", "req-p15", 6, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    @Test
    void 幂等_同键同参重放原结果_异参409() throws Exception {
        MvcResult first = createDoc("req-i1", "doc-i", List.of("en"), List.of(seg("s1", "Hello")))
                .andExpect(status().isOk()).andReturn();
        // 同键同参：重放原成功结果（即使再次执行业务会冲突，也直接返回首次结果）
        MvcResult replay = createDoc("req-i1", "doc-i", List.of("en"), List.of(seg("s1", "Hello")))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生二次效果：草稿版本仍为 1，修订后应为 2
        reviseSource("doc-i", "s1", "req-i2", "Hello v2", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftVersion").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(2);
        // 同键异参 → 409
        createDoc("req-i1", "doc-i-other", List.of("en"), List.of(seg("s1", "Hello")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    }

    @Test
    void 增段落_草稿版本加一_重复段落409() throws Exception {
        createDoc("req-g1", "doc-g", List.of("en"), List.of(seg("s1", "Hello"))).andExpect(status().isOk());
        postJson("/api/documents/doc-g/segments", "alice",
                Map.of("requestId", "req-g2", "segmentId", "s2", "sourceText", "World"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftVersion").value(2))
                .andExpect(jsonPath("$.segments.length()").value(2));
        postJson("/api/documents/doc-g/segments", "alice",
                Map.of("requestId", "req-g3", "segmentId", "s2", "sourceText", "Again"))
                .andExpect(status().isConflict());
    }
}
