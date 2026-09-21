package com.example.starter.api;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Asset;
import com.example.starter.domain.Decision;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.service.AssetService;
import com.example.starter.service.ChannelService;
import com.example.starter.service.DecisionService;
import com.example.starter.service.DraftService;
import com.example.starter.service.GrantService;
import com.example.starter.service.PublishService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层测试：验证路由、时间解析与 400/404/409/422 错误语义。
 */
@WebMvcTest(controllers = {AssetController.class, ChannelController.class, GrantController.class,
        DraftController.class, PublishController.class, DecisionController.class})
@Import(GlobalExceptionHandler.class)
class ApiWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetService assetService;
    @MockitoBean
    private ChannelService channelService;
    @MockitoBean
    private GrantService grantService;
    @MockitoBean
    private DraftService draftService;
    @MockitoBean
    private PublishService publishService;
    @MockitoBean
    private DecisionService decisionService;

    @Test
    void createAssetReturns201() throws Exception {
        when(assetService.create("a1", 60_000L)).thenReturn(new Asset("a1", 60_000));
        mockMvc.perform(post("/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"a1\",\"durationMs\":60000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("a1"))
                .andExpect(jsonPath("$.durationMs").value(60_000));
    }

    @Test
    void invalidAssetReturns400() throws Exception {
        when(assetService.create(any(), any()))
                .thenThrow(ApiException.badRequest("INVALID_ASSET", "素材时长必须为正整数毫秒"));
        mockMvc.perform(post("/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"a1\",\"durationMs\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ASSET"));
    }

    @Test
    void duplicateAssetReturns409() throws Exception {
        when(assetService.create(any(), any()))
                .thenThrow(ApiException.conflict("ASSET_EXISTS", "素材已存在: a1"));
        mockMvc.perform(post("/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"a1\",\"durationMs\":60000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_EXISTS"));
    }

    @Test
    void replaceDraftReturns422OnBusinessRuleViolation() throws Exception {
        when(draftService.replace(anyString(), any(), anyString(), anyLong(), anyList()))
                .thenThrow(ApiException.unprocessable("SEGMENT_OVERLAP", "片段时间重叠"));
        mockMvc.perform(put("/channels/c1/days/2026-09-21/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"expectedDraftVersion\":0,"
                                + "\"segments\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGMENT_OVERLAP"));
    }

    @Test
    void replaceDraftReturns409OnVersionConflict() throws Exception {
        when(draftService.replace(anyString(), any(), anyString(), anyLong(), anyList()))
                .thenThrow(ApiException.conflict("DRAFT_VERSION_CONFLICT", "草稿版本不符"));
        mockMvc.perform(put("/channels/c1/days/2026-09-21/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"expectedDraftVersion\":3,"
                                + "\"segments\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_VERSION_CONFLICT"));
    }

    @Test
    void replaceDraftOkReturnsFormattedTimes() throws Exception {
        DraftSegment segment = new DraftSegment("s1", "a1",
                Instant.parse("2026-09-21T02:00:00Z"), Instant.parse("2026-09-21T02:01:00Z"));
        when(draftService.replace(eq("c1"), eq(LocalDate.of(2026, 9, 21)), eq("r1"), eq(0L),
                anyList()))
                .thenReturn(new Draft("c1", LocalDate.of(2026, 9, 21), 1, List.of(segment)));
        mockMvc.perform(put("/channels/c1/days/2026-09-21/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"expectedDraftVersion\":0,"
                                + "\"segments\":[{\"segmentId\":\"s1\",\"assetId\":\"a1\","
                                + "\"start\":\"2026-09-21T10:00:00.000+08:00\","
                                + "\"end\":\"2026-09-21T10:01:00.000+08:00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].start")
                        .value("2026-09-21T10:00:00.000+08:00"))
                .andExpect(jsonPath("$.segments[0].end")
                        .value("2026-09-21T10:01:00.000+08:00"));
    }

    @Test
    void invalidDayFormatReturns400() throws Exception {
        mockMvc.perform(put("/channels/c1/days/2026-13-99/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"expectedDraftVersion\":0,"
                                + "\"segments\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void invalidSegmentTimeReturns400() throws Exception {
        mockMvc.perform(put("/channels/c1/days/2026-09-21/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"expectedDraftVersion\":0,"
                                + "\"segments\":[{\"segmentId\":\"s1\",\"assetId\":\"a1\","
                                + "\"start\":\"not-a-time\","
                                + "\"end\":\"2026-09-21T10:01:00.000+08:00\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME"));
    }

    @Test
    void publishReturns409OnPublishedVersionConflict() throws Exception {
        when(publishService.publish(anyString(), any(), anyString(), anyLong(), anyLong()))
                .thenThrow(ApiException.conflict("PUBLISHED_VERSION_CONFLICT", "发布版本冲突"));
        mockMvc.perform(post("/channels/c1/days/2026-09-21/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"draftVersion\":1,"
                                + "\"expectedPublishedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLISHED_VERSION_CONFLICT"));
    }

    @Test
    void publishOkReturnsSnapshot() throws Exception {
        when(publishService.publish(eq("c1"), eq(LocalDate.of(2026, 9, 21)), eq("r1"),
                eq(1L), eq(0L)))
                .thenReturn(new PublishedSchedule("c1", LocalDate.of(2026, 9, 21), 1, 1,
                        List.of()));
        mockMvc.perform(post("/channels/c1/days/2026-09-21/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"draftVersion\":1,"
                                + "\"expectedPublishedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.draftVersion").value(1));
    }

    @Test
    void revokeGrantReturns404WhenMissing() throws Exception {
        when(grantService.revoke(anyString(), anyString()))
                .thenThrow(ApiException.notFound("GRANT_NOT_FOUND", "授权不存在"));
        mockMvc.perform(post("/grants/g1/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void revokeGrantOk() throws Exception {
        Grant grant = new Grant("g1", "c1", "a1", Instant.parse("2026-09-21T00:00:00Z"),
                Instant.parse("2026-09-22T00:00:00Z"), true);
        when(grantService.revoke("g1", "r1")).thenReturn(grant);
        mockMvc.perform(post("/grants/g1/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.validFrom").value("2026-09-21T08:00:00.000+08:00"));
    }

    @Test
    void decisionFallbackOk() throws Exception {
        when(decisionService.decide(eq("c1"), any()))
                .thenReturn(Decision.fallback("fb", "NO_PUBLISHED_SCHEDULE"));
        mockMvc.perform(get("/channels/c1/decision")
                        .param("at", "2026-09-21T10:00:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("FALLBACK"))
                .andExpect(jsonPath("$.assetId").value("fb"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
    }

    @Test
    void decisionInvalidTimeReturns400() throws Exception {
        mockMvc.perform(get("/channels/c1/decision").param("at", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME"));
    }

    @Test
    void decisionUnknownChannelReturns404() throws Exception {
        when(decisionService.decide(anyString(), any()))
                .thenThrow(ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在"));
        mockMvc.perform(get("/channels/nope/decision")
                        .param("at", "2026-09-21T10:00:00.000+08:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BODY"));
    }
}
