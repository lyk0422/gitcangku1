package com.example.starter.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地演示数据初始化（仅 local profile）：启动时在 H2 内存库写入一条观测、两个附件、一条规则版本，
 * 并提交一次脱敏，便于本地直接体验完整业务链路。数据随 JVM 生命周期存在，不依赖外部数据库。
 */
@Component
@Profile("local")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String OBSERVATION_ID = "demo-obs-1";
    private static final String ATTACHMENT_1 = "demo-att-1";
    private static final String ATTACHMENT_2 = "demo-att-2";
    private static final String RULE_V1 = "rule-v1";

    private final ObservationService observationService;
    private final RedactionService redactionService;

    public DemoDataSeeder(ObservationService observationService, RedactionService redactionService) {
        this.observationService = observationService;
        this.redactionService = redactionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException e) {
            // 演示数据已存在等场景不阻断启动
            log.warn("demo data seeding skipped: {}", e.getMessage());
        }
    }

    private void seed() {
        if (observationService.safeExists(OBSERVATION_ID)) {
            log.info("demo data already present, skip seeding");
            return;
        }
        observationService.create(new CreateObservationRequest(
                "seed-create-1", OBSERVATION_ID, "1号井口", "12.345", "含身份证与坐标的巡检记录"));
        redactionService.registerRule(new RegisterRuleRequest(
                "seed-rule-1", RULE_V1, "身份证号与手机号脱敏规则 v1"), RequesterRole.REDACTION_OFFICER);
        redactionService.registerAttachment(OBSERVATION_ID, new RegisterAttachmentRequest(
                "seed-att-1", OBSERVATION_ID, ATTACHMENT_1,
                "巡检照片：井口编号 JK-01，人员身份证 110101199001011234，坐标 39.9087,116.3975"),
                RequesterRole.REDACTION_OFFICER);
        redactionService.registerAttachment(OBSERVATION_ID, new RegisterAttachmentRequest(
                "seed-att-2", OBSERVATION_ID, ATTACHMENT_2,
                "音频转写：负责人张三，电话 13800001111"),
                RequesterRole.REDACTION_OFFICER);
        redactionService.redact(OBSERVATION_ID, new RedactAttachmentsRequest(
                        "seed-redact-1", "demo-red-1",
                        List.of(new RedactAttachmentsRequest.RedactItem(
                                ATTACHMENT_1, RULE_V1, "人员证件号已替换为 ***"))),
                RequesterRole.REDACTION_OFFICER);
        log.info("demo data seeded: observation={}, attachments={},{}, rule={}, redaction=demo-red-1",
                OBSERVATION_ID, ATTACHMENT_1, ATTACHMENT_2, RULE_V1);
    }
}
