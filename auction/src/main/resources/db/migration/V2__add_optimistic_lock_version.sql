-- PostgreSQL: 비관적 락에서 낙관적 락으로 전환하기 위한 version 컬럼.
-- 기존 행이 있는 환경에서도 NOT NULL 추가가 실패하지 않도록 단계적으로 적용한다.

ALTER TABLE auction
    ADD COLUMN IF NOT EXISTS version BIGINT;

UPDATE auction
SET version = 0
WHERE version IS NULL;

ALTER TABLE auction
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL;

COMMENT ON COLUMN auction.version IS 'JPA optimistic lock version';

-- 애플리케이션을 이전 버전으로 롤백해도 version 컬럼은 유지한다.
-- 비관적 락 구현은 이 추가 컬럼의 영향을 받지 않는다.
