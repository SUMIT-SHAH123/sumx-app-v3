CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email CITEXT NOT NULL UNIQUE,
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  password_hash TEXT,                      -- NULL for Google-only accounts
  username CITEXT NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_]{3,20}$'),
  sumx_id CITEXT NOT NULL UNIQUE,          -- e.g. @sumit4821
  display_name TEXT NOT NULL,
  date_of_birth DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS profiles (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  avatar_url TEXT, banner_url TEXT,
  banner_color TEXT NOT NULL DEFAULT '#006BFF',
  accent_color TEXT NOT NULL DEFAULT '#00D9FF',
  about TEXT NOT NULL DEFAULT '',
  pronouns TEXT, custom_status TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS linked_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  provider_user_id TEXT NOT NULL,
  email CITEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_user_id),
  UNIQUE (user_id, provider)
);

CREATE TABLE IF NOT EXISTS sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash TEXT NOT NULL UNIQUE,
  device TEXT, ip TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions(user_id) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS email_codes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose TEXT NOT NULL CHECK (purpose IN ('verify_email','reset_password')),
  code_hash TEXT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS email_codes_lookup ON email_codes(user_id, purpose) WHERE used_at IS NULL;

-- ================= Phase 2/3: social + messaging =================
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS friend_requests (          -- pending only; row is deleted when resolved
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  from_user UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_user UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (from_user, to_user), CHECK (from_user <> to_user)
);
CREATE INDEX IF NOT EXISTS friend_requests_to_idx ON friend_requests(to_user);

CREATE TABLE IF NOT EXISTS friendships (              -- one row per pair, user_low < user_high
  user_low UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  user_high UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_low, user_high), CHECK (user_low < user_high)
);
CREATE INDEX IF NOT EXISTS friendships_high_idx ON friendships(user_high);

CREATE TABLE IF NOT EXISTS blocks (
  blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id), CHECK (blocker_id <> blocked_id)
);
CREATE INDEX IF NOT EXISTS blocks_blocked_idx ON blocks(blocked_id);

CREATE TABLE IF NOT EXISTS conversations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dm_key TEXT NOT NULL UNIQUE,                         -- "<low>:<high>"
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  muted BOOLEAN NOT NULL DEFAULT FALSE,
  hidden BOOLEAN NOT NULL DEFAULT FALSE,
  cleared_at TIMESTAMPTZ NOT NULL DEFAULT 'epoch'::timestamptz,
  PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS conv_members_user_idx ON conversation_members(user_id);

CREATE TABLE IF NOT EXISTS messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
  reply_to UUID REFERENCES messages(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  edited_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS messages_conv_idx ON messages(conversation_id, created_at DESC);

-- ================= Phase 4: profiles, activity, settings, notifications, files, calls =================
ALTER TABLE users ADD COLUMN IF NOT EXISTS username_changed_at TIMESTAMPTZ;
ALTER TABLE profiles
  ADD COLUMN IF NOT EXISTS background_url TEXT,
  ADD COLUMN IF NOT EXISTS banner_position INT NOT NULL DEFAULT 50 CHECK (banner_position BETWEEN 0 AND 100),
  ADD COLUMN IF NOT EXISTS frame TEXT NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS decoration TEXT NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS effect TEXT NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS name_style TEXT NOT NULL DEFAULT 'plain',
  ADD COLUMN IF NOT EXISTS theme TEXT NOT NULL DEFAULT 'midnight';

CREATE TABLE IF NOT EXISTS user_settings (user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, data JSONB NOT NULL DEFAULT '{}');

CREATE TABLE IF NOT EXISTS activities (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN ('playing','watching','building','custom','using_sumx')),
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 60), details TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS listening_activities (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  platform TEXT NOT NULL, title TEXT NOT NULL, artist TEXT, artwork_url TEXT, duration_sec INT,
  source TEXT NOT NULL DEFAULT 'manual', started_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE IF NOT EXISTS notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
  type TEXT NOT NULL, data JSONB NOT NULL DEFAULT '{}',
  read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS notifications_user_idx ON notifications(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS attachments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  uploader_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
  message_id UUID REFERENCES messages(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('image','file','voice','avatar','banner','background')),
  mime TEXT NOT NULL, size BIGINT NOT NULL, original_name TEXT NOT NULL, storage_name TEXT NOT NULL, duration_ms INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS attachments_msg_idx ON attachments(message_id);

CREATE TABLE IF NOT EXISTS message_reactions (
  message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji TEXT NOT NULL CHECK (length(emoji) <= 16), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, user_id, emoji));

ALTER TABLE messages ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMPTZ, ADD COLUMN IF NOT EXISTS gif_url TEXT;
DO $$ BEGIN
  ALTER TABLE messages DROP CONSTRAINT IF EXISTS messages_body_check;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='messages_body_len') THEN
    ALTER TABLE messages ADD CONSTRAINT messages_body_len CHECK (length(body) <= 4000);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS calls (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  caller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  callee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('voice','video')),
  status TEXT NOT NULL DEFAULT 'ringing' CHECK (status IN ('ringing','accepted','declined','missed','cancelled','ended')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), answered_at TIMESTAMPTZ, ended_at TIMESTAMPTZ);
CREATE INDEX IF NOT EXISTS calls_user_idx ON calls(caller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS calls_callee_idx ON calls(callee_id, created_at DESC);
