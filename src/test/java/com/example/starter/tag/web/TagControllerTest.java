package com.example.starter.tag.web;

import com.example.starter.tag.AbstractTagIntegrationTest;
import com.example.starter.tag.FixedClockConfig;
import com.example.starter.tag.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 状态码与响应体测试：409/422/400 与时间线 JSON。
 */
@Import(FixedClockConfig.class)
class TagControllerTest extends AbstractTagIntegrationTest {

    private final MockMvc mockMvc;

    @Autowired
    private TagService tagService;

    @Autowired
    TagControllerTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void registerAttachDetachHandoffChainOverHttp() throws Exception {
        mockMvc.perform(post("/api/tags/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tagCode":"L","siteCode":"S1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tags/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","tagCode":"L","animalCode":"A1",
                                 "siteCode":"S1","attachAt":"2026-03-10T09:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.tagCode").value("L"));

        // 同参重放返回 201 且 replay=true。
        mockMvc.perform(post("/api/tags/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","tagCode":"L","animalCode":"A1",
                                 "siteCode":"S1","attachAt":"2026-03-10T09:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(true));

        // 异参同 requestId 返回 409。
        mockMvc.perform(post("/api/tags/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","tagCode":"L","animalCode":"A2",
                                 "siteCode":"S1","attachAt":"2026-03-10T11:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 09:59 交叉附着 -> 422。
        mockMvc.perform(post("/api/tags/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-2","tagCode":"L","animalCode":"A2",
                                 "siteCode":"S1","attachAt":"2026-03-10T09:59:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        mockMvc.perform(post("/api/tags/detach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tagCode":"L","siteCode":"S1",
                                 "detachAt":"2026-03-10T10:00:00Z"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tags/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-h1","tagCode":"L","fromSiteCode":"S1",
                                 "toSiteCode":"S2","initiateAt":"2026-03-10T10:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.replay").value(false));

        String handoffCode = tagService.tagTimeline("L").getHandoffs().get(0).getHandoffCode();

        // 非目标站点确认 -> 422。
        mockMvc.perform(post("/api/tags/handoffs/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handoffCode":"%s","siteCode":"S1",
                                 "confirmAt":"2026-03-10T10:00:00Z"}
                                """.formatted(handoffCode)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/tags/handoffs/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handoffCode":"%s","siteCode":"S2",
                                 "confirmAt":"2026-03-10T10:00:00Z"}
                                """.formatted(handoffCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").value("2026-03-10T10:00:00Z"));

        // 已确认交接不得取消 -> 409。
        mockMvc.perform(post("/api/tags/handoffs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"handoffCode":"%s","siteCode":"S1",
                                 "at":"2026-03-10T10:30:00Z"}
                                """.formatted(handoffCode)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/tags/L/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderSiteCode").value("S2"))
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.handoffs[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.handoffs[0].confirmedAt")
                        .value("2026-03-10T10:00:00Z"));

        mockMvc.perform(get("/api/tags/animals/A1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animalCode").value("A1"))
                .andExpect(jsonPath("$.attachments[0].detachAt")
                        .value("2026-03-10T10:00:00Z"));
    }

    @Test
    void invalidBodyReturns400AndUnknownTag422() throws Exception {
        mockMvc.perform(post("/api/tags/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","tagCode":"L","animalCode":"A1",
                                 "siteCode":"S1","attachAt":"2026-03-10T09:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/tags/UNKNOWN/timeline"))
                .andExpect(status().isUnprocessableEntity());
    }
}
