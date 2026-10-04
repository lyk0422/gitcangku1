-- 本地入口合成演示数据：仅在空库时插入；测试上下文启动后由用例清理，不影响用例隔离。
INSERT INTO document (target_languages, draft_version, published_version, term_version)
SELECT 'en,ja', 1, 0, 0 WHERE NOT EXISTS (SELECT 1 FROM document);
INSERT INTO segment (document_id, segment_id, source_text, source_version)
SELECT document_id, 's1', '你好世界', 1 FROM document
WHERE NOT EXISTS (SELECT 1 FROM segment);