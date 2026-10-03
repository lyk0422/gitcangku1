package com.example.starter.aging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.aging.web.ApiException;
import com.example.starter.aging.web.ApiViews.ReleaseView;
import com.example.starter.aging.web.ApiViews.TempView;
import com.example.starter.aging.web.ApiViews.VoidResultView;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * 测温作废与替代测试：作废影响未放行资格，已放行批次保留审核快照。
 */
class TemperatureVoidApiTest extends AbstractAgingTest {

    @Test
    void voidBeforeReleaseAffectsEligibilityAndReplacementRestores() {
        long batchId = newBatch("VOID-001");
        fillAllDays(batchId);
        // 第 2 天唯一合格温度作废（无替代）：放行拒绝并返回该日期
        long tempId = jdbc.queryForObject(
                "SELECT id FROM temperature_record WHERE batch_id = ? AND temp_date = '2026-01-02'",
                Long.class, batchId);
        VoidResultView voided = service.voidTemperature(tempId, "读数异常", null, null, null);
        assertThat(voided.voidedId()).isEqualTo(tempId);
        assertThat(voided.replacement()).isNull();

        assertThatThrownBy(() -> service.release(batchId, "void001-rel"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.details().toString()).contains("2026-01-02");
                });
        assertThat(countRows("release_record")).isZero();

        // 追加替代记录后重新审核通过
        TempView replacement = service.addTemperature(batchId, LocalDate.of(2026, 1, 2),
                new BigDecimal("9.5"), "qa2");
        ReleaseView release = service.release(batchId, "void001-rel");
        assertThat(release.snapshot()).hasSize(7);
        // 快照引用的是替代后的有效记录，而非已作废记录
        assertThat(release.snapshot().get(1).temperatureId()).isEqualTo(replacement.id());
    }

    @Test
    void doubleVoidRejected() {
        long batchId = newBatch("VOID-002");
        fillDay(batchId, 0);
        long tempId = jdbc.queryForObject(
                "SELECT id FROM temperature_record WHERE batch_id = ?", Long.class, batchId);
        service.voidTemperature(tempId, "误录", null, null, null);
        assertThatThrownBy(() -> service.voidTemperature(tempId, "再次作废", null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void voidWithReplacementIsAtomic() {
        long batchId = newBatch("VOID-003");
        fillDay(batchId, 0);
        long tempId = jdbc.queryForObject(
                "SELECT id FROM temperature_record WHERE batch_id = ?", Long.class, batchId);
        VoidResultView result = service.voidTemperature(tempId, "传感器故障",
                LocalDate.of(2026, 1, 1), new BigDecimal("10.5"), "qa2");
        assertThat(result.replacement()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM temperature_record WHERE id = ?", String.class, tempId))
                .isEqualTo("VOID");
        assertThat(jdbc.queryForObject(
                "SELECT replaced_by_id FROM temperature_record WHERE id = ?", Long.class, tempId))
                .isEqualTo(result.replacement().id());
    }

    @Test
    void releasedBatchKeepsSnapshotAfterVoid() throws Exception {
        long batchId = newBatch("VOID-004");
        fillAllDays(batchId);
        ReleaseView release = service.release(batchId, "void004-rel");
        long snapshotTempId = release.snapshot().get(0).temperatureId();

        // 放行后作废快照中的温度记录：快照与放行结果不变
        service.voidTemperature(snapshotTempId, "事后复核", null, null, null);
        mockMvc.perform(get("/api/batches/{id}/board", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.releaseId").value(release.releaseId()))
                .andExpect(jsonPath("$.days[0].releaseSnapshot.temperatureId")
                        .value((int) snapshotTempId));
        // 已放行批次的 requestId 仍按原记录幂等
        mockMvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"void004-rel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(release.releaseId()));
    }
}