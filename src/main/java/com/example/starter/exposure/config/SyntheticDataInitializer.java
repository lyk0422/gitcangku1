package com.example.starter.exposure.config;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.ActivityDayConfig;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.repo.ActivityDayConfigRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 本地入口合成数据初始化：H2 内存库启动后写入演示活动、当日与次日小时预算及两个公告。
 * 幂等：已存在的行跳过，重复启动不报错。测试环境（profile=test）不执行，
 * 测试在各用例内自行构造合成数据。
 */
@Component
@Profile("!test")
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataInitializer.class);

    static final String DEMO_ACTIVITY_ID = "act-demo";
    static final String DEMO_CAMPAIGN_ID = "demo-campaign";
    static final String DEMO_FREE_CAMPAIGN_ID = "demo-campaign-free";
    private static final int DEMO_HOURLY_BUDGET = 10;
    private static final int HOURS_PER_DAY = 24;

    private final Clock clock;
    private final ActivityRepository activityRepository;
    private final ActivityDayConfigRepository dayConfigRepository;
    private final CampaignRepository campaignRepository;
    private final TransactionTemplate txTemplate;

    public SyntheticDataInitializer(Clock clock,
                                    ActivityRepository activityRepository,
                                    ActivityDayConfigRepository dayConfigRepository,
                                    CampaignRepository campaignRepository,
                                    TransactionTemplate txTemplate) {
        this.clock = clock;
        this.activityRepository = activityRepository;
        this.dayConfigRepository = dayConfigRepository;
        this.campaignRepository = campaignRepository;
        this.txTemplate = txTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        txTemplate.executeWithoutResult(status -> {
            long now = clock.millis();
            if (activityRepository.findById(DEMO_ACTIVITY_ID).isEmpty()) {
                activityRepository.insert(new Activity(DEMO_ACTIVITY_ID, now));
            }
            LocalDate today = LocalDate.now(clock);
            for (LocalDate date : List.of(today, today.plusDays(1))) {
                if (dayConfigRepository.findConfig(DEMO_ACTIVITY_ID, date).isEmpty()) {
                    dayConfigRepository.insert(new ActivityDayConfig(
                            DEMO_ACTIVITY_ID, date,
                            DEMO_HOURLY_BUDGET * HOURS_PER_DAY,
                            Collections.nCopies(HOURS_PER_DAY, DEMO_HOURLY_BUDGET),
                            1L, now));
                }
            }
            if (campaignRepository.findById(DEMO_CAMPAIGN_ID).isEmpty()) {
                campaignRepository.insert(new Campaign(
                        DEMO_CAMPAIGN_ID, 1_000, 10, now, DEMO_ACTIVITY_ID));
            }
            if (campaignRepository.findById(DEMO_FREE_CAMPAIGN_ID).isEmpty()) {
                campaignRepository.insert(new Campaign(
                        DEMO_FREE_CAMPAIGN_ID, 1_000, 10, now, null));
            }
        });
        log.info("synthetic demo data ready: activity={}, campaigns={},{}",
                DEMO_ACTIVITY_ID, DEMO_CAMPAIGN_ID, DEMO_FREE_CAMPAIGN_ID);
    }
}
