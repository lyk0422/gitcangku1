-- 本地合成演示数据（标准 test 每个用例前会清空业务表，不影响断言）。
-- 一条完整链路：文档 1（en/ja）-> 段落 s1 -> 两语种译文与批准 -> 发布版本 1 -> 归档包 1（ACTIVE）。
-- 所有业务时刻均为 UTC；证据 JSON 与 ArchiveEvidence 结构一致，可直接通过归档明细接口读取。

INSERT INTO document (document_id, target_languages, draft_version, published_version, term_version)
VALUES (1, 'en,ja', 5, 1, 0);

INSERT INTO segment (document_id, segment_id, source_text, source_version)
VALUES (1, 's1', 'hello world', 1);

INSERT INTO translation (document_id, segment_id, language, content, author, source_version,
                         translation_version, term_version)
VALUES (1, 's1', 'en', 'hello', 'alice', 1, 1, 0),
       (1, 's1', 'ja', 'konnichiwa', 'carol', 1, 1, 0);

INSERT INTO approval (document_id, segment_id, language, reviewer, source_version, translation_version)
VALUES (1, 's1', 'en', 'bob', 1, 1),
       (1, 's1', 'ja', 'dave', 1, 1);

INSERT INTO release_snapshot (document_id, published_version, snapshot_json, created_at)
VALUES (1, 1,
'{"documentId":1,"publishedVersion":1,"draftVersion":5,"termVersion":0,"targetLanguages":["en","ja"],"terms":[],"segments":[{"segmentId":"s1","sourceText":"hello world","sourceVersion":1,"translations":[{"language":"en","content":"hello","author":"alice","translationVersion":1,"sourceVersion":1,"termVersion":0,"reviewer":"bob"},{"language":"ja","content":"konnichiwa","author":"carol","translationVersion":1,"sourceVersion":1,"termVersion":0,"reviewer":"dave"}]}]}',
TIMESTAMP '2026-09-29 08:00:00');

INSERT INTO archive_package (archive_id, document_id, published_version, status, created_by, created_at,
                             package_json)
VALUES (1, 1, 1, 'ACTIVE', 'archiver', TIMESTAMP '2026-09-29 08:30:00',
'{"publishedVersion":1,"termVersion":0,"targetLanguages":["en","ja"],"terms":[],"segments":[{"segmentId":"s1","sourceVersion":1,"sourceText":"hello world","translations":[{"language":"en","content":"hello","author":"alice","translationVersion":1,"sourceVersion":1,"termVersion":0,"reviewer":"bob"},{"language":"ja","content":"konnichiwa","author":"carol","translationVersion":1,"sourceVersion":1,"termVersion":0,"reviewer":"dave"}]}],"approvals":[{"segmentId":"s1","language":"en","reviewer":"bob","sourceVersion":1,"translationVersion":1},{"segmentId":"s1","language":"ja","reviewer":"dave","sourceVersion":1,"translationVersion":1}],"releaseSummary":{"publishedVersion":1,"releasedAt":"2026-09-29T08:00:00Z","segmentCount":1,"requiredLanguageCount":2,"translationCount":2,"approvalCount":2,"termVersion":0,"ruleCount":0}}');
