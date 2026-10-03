package com.example.starter.cue;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import com.example.starter.cue.repo.CueVersionRepository;
import com.example.starter.cue.service.BusinessException;
import com.example.starter.cue.service.CueVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本编排与冻结校验的真实 H2 测试。
 */
@SpringBootTest
@Import(CueTestConfig.class)
class CueVersionServiceTest {

    @Autowired
    private CueVersionService service;
    @Autowired
    private CueVersionRepository versions;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cue_execution");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    private CueItem cue(int seq, int duration) {
        return new CueItem(0, 0, seq, "LIGHT", "口令" + seq, duration);
    }

    @Test
    void freezeValidVersion() {
        long id = service.createVersion("茶馆", List.of(cue(1, 30), cue(2, 60), cue(3, 5)));
        CueVersion frozen = service.freezeVersion(id);
        assertEquals(CueVersion.STATUS_FROZEN, frozen.status());
        assertEquals(1, frozen.versionNo());
        assertEquals(3, versions.findItems(id).size());
    }

    @Test
    void invalidDurationAndDuplicateSeqRejectWholeVersion() {
        long id = service.createVersion("茶馆", List.of(
                cue(1, 0), cue(2, 601), cue(3, 10),
                new CueItem(0, 0, 3, "SOUND", "音效", 20)));
        BusinessException e = assertThrows(BusinessException.class, () -> service.freezeVersion(id));
        assertTrue(e.getMessage().contains("0 超出 1 至 600"), e.getMessage());
        assertTrue(e.getMessage().contains("601 超出 1 至 600"), e.getMessage());
        assertTrue(e.getMessage().contains("序号 3 重复"), e.getMessage());
        // 整个版本保持草稿，不能冻结，也不只丢弃坏提示点
        assertEquals(CueVersion.STATUS_DRAFT, versions.findVersion(id).orElseThrow().status());
        assertEquals(4, versions.findItems(id).size());
    }

    @Test
    void modificationCreatesNewVersionAndDiffShowsChanges() {
        long v1 = service.createVersion("雷雨", List.of(cue(1, 30), cue(2, 60), cue(3, 5)));
        service.freezeVersion(v1);
        long v2 = service.createVersion("雷雨", List.of(
                cue(1, 30), new CueItem(0, 0, 2, "LIGHT", "口令2", 90), cue(4, 15)));
        service.freezeVersion(v2);

        assertEquals(2, versions.findVersion(v2).orElseThrow().versionNo());
        Map<String, Object> diff = service.diff(v1, v2);
        assertEquals(1, ((List<?>) diff.get("added")).size());
        assertEquals(1, ((List<?>) diff.get("removed")).size());
        assertEquals(1, ((List<?>) diff.get("changed")).size());
        assertEquals(1, diff.get("unchanged"));
        // 旧版本内容不受新版本影响
        assertEquals(3, versions.findItems(v1).size());
    }
}