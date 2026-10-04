package com.example.starter.cue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 测试基类：独立命名H2内存库，每个用例前清理场景数据。 */
@SpringBootTest
abstract class CueTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CueService cueService;

    @Autowired
    protected SessionService sessionService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM cue_failure");
        jdbc.update("DELETE FROM cue_execution");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    /** 创建并冻结一个合法版本，返回版本ID。 */
    protected long frozenVersion(List<CueDtos.CueInput> cues) {
        long versionId = cueService.createVersion(new CueDtos.CreateVersionRequest(cues));
        cueService.freeze(versionId);
        return versionId;
    }

    protected static CueDtos.CueInput cue(int seq, CueType type, String phrase, int seconds) {
        return new CueDtos.CueInput(seq, type, phrase, seconds);
    }

    /** 创建场次并开始，返回场次ID。 */
    protected long runningSession(long versionId) {
        long sessionId = sessionService.createSession(new CueDtos.CreateSessionRequest(versionId));
        sessionService.start(sessionId);
        return sessionId;
    }
}