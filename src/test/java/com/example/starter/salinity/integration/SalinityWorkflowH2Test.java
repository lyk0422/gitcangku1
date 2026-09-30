package com.example.starter.salinity.integration;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.CorrectionRequest;
import com.example.starter.salinity.dto.CorrectionView;
import com.example.starter.salinity.dto.SampleView;
import com.example.starter.salinity.dto.SealResult;
import com.example.starter.salinity.dto.SealedProfileView;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.dto.VoyageDataView;
import com.example.starter.salinity.exception.UnprocessableException;
import com.example.starter.salinity.service.SalinityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 H2（MODE=MySQL，自动建表）业务集成测试：
 * 主流程、缺深度封存失败不锁定、双重幂等、封存后替换拒绝与勘误追加、原始重测保留。
 */
@SpringBootTest
class SalinityWorkflowH2Test {

    private static final Instant COLLECTED = Instant.parse("2026-09-30T08:00:00Z");

    @Autowired
    private SalinityService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();

    private String voyage(String prefix) {
        return prefix + "-" + seq.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SubmitSampleRequest sample(String voyage, String station, int depth, String salinity,
                                       String sampleNoSuffix, String requestIdSuffix) {
        return new SubmitSampleRequest(voyage, station, depth, new BigDecimal(salinity),
                COLLECTED, "NO-" + sampleNoSuffix, "REQ-" + requestIdSuffix);
    }

    private void submitStation3Depths(String voyage, String station, String tag,
                                      String s0, String s50, String s100) {
        service.submitSample(sample(voyage, station, 0, s0, tag + "-0", tag + "-r0"));
        service.submitSample(sample(voyage, station, 50, s50, tag + "-50", tag + "-r50"));
        service.submitSample(sample(voyage, station, 100, s100, tag + "-100", tag + "-r100"));
    }

    private void adopt(String voyage, String sampleNo, String reqSuffix) {
        service.adopt(new AdoptRequest(voyage, sampleNo, "ADOPT-" + reqSuffix));
    }

    @Test
    @DisplayName("主流程：重测保留、单采纳、封存后剖面按站位深度升序固化")
    void fullWorkflowWithRetestAndSeal() {
        String voyage = voyage("WF");

        // 站位 S：0 米先采一版，再重测一版（原始重测必须保留）。
        service.submitSample(sample(voyage, "S", 0, "35.100", "S0-v1", "S0-r1"));
        service.submitSample(sample(voyage, "S", 0, "35.200", "S0-v2", "S0-r2"));
        submitStation3Depths(voyage, "S", "base", "35.200", "34.800", "34.200");
        // 注意：base 的 0 米是同位置第三个版本；改用站位 A 演示三深度更清晰。
        submitStation3Depths(voyage, "A", "A", "35.000", "34.500", "34.000");

        // 站位 S 采纳 v2（35.200），站位 A 采纳各自唯一版本。
        adopt(voyage, "NO-S0-v2", "s0v2");
        adopt(voyage, "NO-base-50", "s50");
        adopt(voyage, "NO-base-100", "s100");
        adopt(voyage, "NO-A-0", "a0");
        adopt(voyage, "NO-A-50", "a50");
        adopt(voyage, "NO-A-100", "a100");

        SealResult seal = service.seal(voyage);
        assertThat(seal.sealedAtUtc()).isNotNull();
        assertThat(seal.sealedProfile()).extracting(SealedProfileView::station)
                .containsExactly("A", "A", "A", "S", "S", "S");
        assertThat(seal.sealedProfile()).extracting(SealedProfileView::depthM)
                .containsExactly(0, 50, 100, 0, 50, 100);

        // 冻结的 S/0 米是采纳的 v2（35.200），且引用 v2 样本。
        SealedProfileView s0 = seal.sealedProfile().stream()
                .filter(p -> p.station().equals("S") && p.depthM() == 0).findFirst().orElseThrow();
        assertThat(s0.salinityPsu()).isEqualByComparingTo("35.200");
        assertThat(s0.sampleNo()).isEqualTo("NO-S0-v2");

        // 查询：原始样本（含未采纳重测）全部保留；采纳版本每位置一条。
        VoyageDataView data = service.getVoyageData(voyage);
        assertThat(data.sealed()).isTrue();
        long s0RawCount = data.rawSamples().stream()
                .filter(s -> s.station().equals("S") && s.depthM() == 0).count();
        assertThat(s0RawCount).as("0 米原始重测全部保留").isEqualTo(3);
        assertThat(data.adoptedVersions()).filteredOn(s -> s.station().equals("S"))
                .extracting(SampleView::depthM).containsExactly(0, 50, 100);
        assertThat(data.sealedProfile()).hasSize(6);

        // 封存后替换采纳版本被拒绝。
        assertThatThrownBy(() -> adopt(voyage, "NO-S0-v1", "after-seal"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已封存");

        // 封存后只追加勘误，原剖面继续可查且数值不变。
        CorrectionView correction = service.addCorrection(voyage,
                new CorrectionRequest("S", 0, new BigDecimal("35.250"),
                        "实验室复核后修正盐度", "CORR-1")).correction();
        assertThat(correction.sealedProfileId()).isEqualTo(s0.id());

        VoyageDataView after = service.getVoyageData(voyage);
        assertThat(after.corrections()).hasSize(1);
        SealedProfileView s0Still = after.sealedProfile().stream()
                .filter(p -> p.station().equals("S") && p.depthM() == 0).findFirst().orElseThrow();
        assertThat(s0Still.salinityPsu()).as("原冻结剖面不受勘误影响")
                .isEqualByComparingTo("35.200");
        assertThat(s0Still.id()).isEqualTo(s0.id());
    }

    @Test
    @DisplayName("缺 50 米封存返回 422、不锁定航次；补齐后封存成功并输出 0/50/100 固定顺序")
    void missingDepthSealFailsWithoutLocking() {
        String voyage = voyage("MISS");
        submitStation3Depths(voyage, "S", "m", "35.000", "34.500", "34.000");
        adopt(voyage, "NO-m-0", "m0");
        adopt(voyage, "NO-m-100", "m100");
        // 50 米未采纳。

        assertThatThrownBy(() -> service.seal(voyage))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("缺 50 米采纳样本");

        // 失败封存不锁定：航次仍 OPEN，可继续质控。
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM voyages WHERE code = ?", String.class, voyage);
        assertThat(status).isEqualTo("OPEN");
        Integer sealedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sealed_profiles WHERE voyage_code = ?", Integer.class, voyage);
        assertThat(sealedRows).isZero();

        adopt(voyage, "NO-m-50", "m50");
        SealResult result = service.seal(voyage);
        assertThat(result.sealedProfile()).extracting(SealedProfileView::depthM)
                .containsExactly(0, 50, 100);
    }

    @Test
    @DisplayName("越界值返回 422")
    void outOfRangeValuesRejected() {
        String voyage = voyage("RANGE");
        assertThatThrownBy(() -> service.submitSample(
                sample(voyage, "S", -1, "35.000", "neg", "neg-r")))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("深度越界");
        assertThatThrownBy(() -> service.submitSample(
                sample(voyage, "S", 501, "35.000", "deep", "deep-r")))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("深度越界");
        assertThatThrownBy(() -> service.submitSample(
                sample(voyage, "S", 0, "45.001", "hi", "hi-r")))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("盐度越界");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE voyage_code = ?", Integer.class, voyage)).isZero();
    }

    @Test
    @DisplayName("双重防重复：同参重放原样本；编号相同内容不同 409；requestId 复用内容不同 409")
    void idempotencyAndConflict() {
        String voyage = voyage("IDEM");
        SubmitSampleRequest first = sample(voyage, "S", 0, "35.000", "i0", "ir0");
        SampleView created = service.submitSample(first).sample();
        SampleView replayed = service.submitSample(first).sample();
        assertThat(replayed.id()).isEqualTo(created.id());

        // 同采样编号、不同盐度 → 409。
        SubmitSampleRequest sameNoDifferentSalinity =
                sample(voyage, "S", 0, "36.000", "i0", "ir1");
        assertThatThrownBy(() -> service.submitSample(sameNoDifferentSalinity))
                .isInstanceOf(com.example.starter.salinity.exception.ConflictException.class);

        // 同 requestId、不同内容 → 409。
        SubmitSampleRequest sameRequestDifferentContent =
                sample(voyage, "S", 50, "35.000", "i50", "ir0");
        assertThatThrownBy(() -> service.submitSample(sameRequestDifferentContent))
                .isInstanceOf(com.example.starter.salinity.exception.ConflictException.class);

        // 只有一条样本落库。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE voyage_code = ?", Integer.class, voyage)).isEqualTo(1);
    }

    @Test
    @DisplayName("勘误幂等：同参重放返回原勘误；同 requestId 不同内容 409")
    void correctionIdempotency() {
        String voyage = voyage("CORR");
        submitStation3Depths(voyage, "S", "c", "35.000", "34.500", "34.000");
        adopt(voyage, "NO-c-0", "c0");
        adopt(voyage, "NO-c-50", "c50");
        adopt(voyage, "NO-c-100", "c100");
        service.seal(voyage);

        CorrectionRequest req = new CorrectionRequest("S", 50, new BigDecimal("34.600"),
                "复核修正", "CORR-X1");
        CorrectionView first = service.addCorrection(voyage, req).correction();
        CorrectionView replay = service.addCorrection(voyage, req).correction();
        assertThat(replay.id()).isEqualTo(first.id());

        assertThatThrownBy(() -> service.addCorrection(voyage,
                new CorrectionRequest("S", 50, new BigDecimal("34.700"),
                        "不同内容", "CORR-X1")))
                .isInstanceOf(com.example.starter.salinity.exception.ConflictException.class);
    }

    @Test
    @DisplayName("查询不存在的航次返回 404；封存不存在航次返回 404")
    void unknownVoyage404() {
        String voyage = voyage("NOPE");
        assertThatThrownBy(() -> service.getVoyageData(voyage))
                .isInstanceOf(com.example.starter.salinity.exception.NotFoundException.class);
        assertThatThrownBy(() -> service.seal(voyage))
                .isInstanceOf(com.example.starter.salinity.exception.NotFoundException.class);
    }
}
