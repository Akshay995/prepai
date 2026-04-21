-- V1__initial_schema.sql

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Users ──────────────────────────────────────────────
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    password    VARCHAR(255),
    avatar_url  VARCHAR(500),
    provider    VARCHAR(50) DEFAULT 'local',
    provider_id VARCHAR(255),
    role        VARCHAR(50) NOT NULL DEFAULT 'USER',
    plan        VARCHAR(50) NOT NULL DEFAULT 'FREE',
    plan_expires_at TIMESTAMPTZ,
    credits     INTEGER NOT NULL DEFAULT 5,
    email_verified BOOLEAN NOT NULL DEFAULT false,
    verification_token VARCHAR(255),
    reset_token VARCHAR(255),
    reset_token_expires_at TIMESTAMPTZ,
    stripe_customer_id VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    last_login_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_stripe_customer ON users(stripe_customer_id);

-- ── Refresh Tokens ──────────────────────────────────────
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- ── Interview Sessions ──────────────────────────────────
CREATE TABLE interview_sessions (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(255) NOT NULL,
    company     VARCHAR(255),
    type        VARCHAR(100) NOT NULL,
    difficulty  VARCHAR(50) NOT NULL DEFAULT 'MID_LEVEL',
    status      VARCHAR(50) NOT NULL DEFAULT 'SETUP',
    score       INTEGER,
    grade       VARCHAR(50),
    duration_seconds INTEGER,
    question_count INTEGER DEFAULT 0,
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_user ON interview_sessions(user_id);
CREATE INDEX idx_sessions_status ON interview_sessions(status);
CREATE INDEX idx_sessions_created ON interview_sessions(created_at DESC);

-- ── Interview Messages ──────────────────────────────────
CREATE TABLE interview_messages (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id  UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL, -- 'assistant' | 'user'
    content     TEXT NOT NULL,
    question_number INTEGER,
    word_count  INTEGER,
    filler_count INTEGER DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_session ON interview_messages(session_id);

-- ── Session Feedback ────────────────────────────────────
CREATE TABLE session_feedback (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id      UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE UNIQUE,
    summary         TEXT,
    star_score      DECIMAL(3,1),
    speaking_pace   INTEGER,
    filler_rate     DECIMAL(4,1),
    vocal_confidence INTEGER,
    specificity     INTEGER,
    strengths       TEXT[], -- array of strength tags
    improvements    TEXT[], -- array of improvement areas
    next_steps      TEXT[],
    per_answer_json JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feedback_session ON session_feedback(session_id);

-- ── Subscriptions / Payments ────────────────────────────
CREATE TABLE payments (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_payment_id    VARCHAR(255) NOT NULL UNIQUE,
    stripe_subscription_id VARCHAR(255),
    amount               INTEGER NOT NULL, -- in cents
    currency             VARCHAR(10) NOT NULL DEFAULT 'usd',
    plan                 VARCHAR(50) NOT NULL,
    status               VARCHAR(50) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_user ON payments(user_id);

-- ── Updated_at trigger ──────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_sessions_updated_at BEFORE UPDATE ON interview_sessions FOR EACH ROW EXECUTE FUNCTION update_updated_at();
