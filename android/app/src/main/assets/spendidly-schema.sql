CREATE TABLE IF NOT EXISTS categories (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0),
  description TEXT NOT NULL DEFAULT '',
  position INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE TABLE IF NOT EXISTS expenses (
  id TEXT PRIMARY KEY NOT NULL,
  spent_on TEXT NOT NULL CHECK (
    spent_on GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
  ),
  amount_paise INTEGER NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 99999999999999),
  currency TEXT NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  category_id TEXT REFERENCES categories(id),
  description TEXT,
  created_at TEXT NOT NULL,
  source_text TEXT NOT NULL DEFAULT '',
  deleted_at TEXT
) STRICT;

CREATE INDEX IF NOT EXISTS expenses_chronological
  ON expenses (spent_on DESC, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS expenses_category_chronological
  ON expenses (category_id, spent_on DESC, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS provider_settings (
  provider TEXT PRIMARY KEY NOT NULL,
  model TEXT NOT NULL,
  encrypted_key BLOB NOT NULL,
  iv BLOB NOT NULL
) STRICT;

CREATE TABLE IF NOT EXISTS chats (
  id TEXT PRIMARY KEY NOT NULL,
  title TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  provider TEXT,
  model TEXT,
  history TEXT NOT NULL,
  transcript TEXT NOT NULL
) STRICT;
