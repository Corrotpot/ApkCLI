//! Conversation store.
//!
//! A million-token session is tens of megabytes of text, thousands of tool
//! results and hundreds of file snapshots. Keeping that in a JVM object graph
//! means a multi-second cold start and a heap that the low-memory killer
//! notices, so it lives in SQLite instead — WAL mode, FTS5 over message text,
//! and large tool outputs spilled into a blob table rather than inlined.
//!
//! The store is opened by three processes (`:ui` reads, `:engine` writes,
//! `:tools` writes tool results). WAL plus a busy timeout is exactly the case
//! WAL was designed for, so no custom locking is needed.
//!
//! FFI shape: one `call(op, payload_json) -> result_json` dispatcher instead of
//! fifty JNI entry points. Crossing JNI once per operation costs ~1µs, which is
//! irrelevant next to the SQLite work, and it means adding an operation touches
//! one match arm instead of three files.

use crate::error::{CoreError, Result};
use parking_lot::Mutex;
use rusqlite::{params, params_from_iter, Connection, OptionalExtension};
use serde_json::{json, Map, Value};
use std::time::{SystemTime, UNIX_EPOCH};

const SCHEMA_VERSION: i64 = 1;

pub struct Store {
    conn: Mutex<Connection>,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn s(v: &Value, k: &str) -> Result<String> {
    v.get(k)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| CoreError::invalid(format!("missing string field '{k}'")))
}

fn s_or(v: &Value, k: &str, default: &str) -> String {
    v.get(k)
        .and_then(Value::as_str)
        .unwrap_or(default)
        .to_string()
}

fn opt_s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::to_owned)
}

fn i(v: &Value, k: &str, default: i64) -> i64 {
    v.get(k).and_then(Value::as_i64).unwrap_or(default)
}

fn b(v: &Value, k: &str, default: bool) -> bool {
    v.get(k).and_then(Value::as_bool).unwrap_or(default)
}

impl Store {
    pub fn open(path: &str) -> Result<Store> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.pragma_update(None, "temp_store", "MEMORY")?;
        // 16 MiB page cache; negative means KiB.
        conn.pragma_update(None, "cache_size", -16_384)?;
        conn.busy_timeout(std::time::Duration::from_secs(10))?;

        let store = Store {
            conn: Mutex::new(conn),
        };
        store.migrate()?;
        Ok(store)
    }

    fn migrate(&self) -> Result<()> {
        let conn = self.conn.lock();
        conn.execute_batch(
            r#"
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS sessions(
  id            TEXT PRIMARY KEY,
  title         TEXT    NOT NULL DEFAULT '',
  workspace     TEXT    NOT NULL DEFAULT '',
  agent_id      TEXT,
  endpoint_id   TEXT,
  model         TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  archived      INTEGER NOT NULL DEFAULT 0,
  next_seq      INTEGER NOT NULL DEFAULT 0,
  live_tokens   INTEGER NOT NULL DEFAULT 0,
  total_tokens  INTEGER NOT NULL DEFAULT 0,
  compactions   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages(
  id         TEXT PRIMARY KEY,
  session_id TEXT    NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq        INTEGER NOT NULL,
  role       TEXT    NOT NULL,
  status     TEXT    NOT NULL DEFAULT 'complete',
  created_at INTEGER NOT NULL,
  content    TEXT    NOT NULL DEFAULT '[]',
  text       TEXT    NOT NULL DEFAULT '',
  tokens     INTEGER NOT NULL DEFAULT 0,
  pinned     INTEGER NOT NULL DEFAULT 0,
  archived   INTEGER NOT NULL DEFAULT 0,
  digest_id  TEXT,
  UNIQUE(session_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_msg_live ON messages(session_id, archived, seq);
CREATE INDEX IF NOT EXISTS idx_msg_seq  ON messages(session_id, seq);

CREATE TABLE IF NOT EXISTS tool_calls(
  id         TEXT PRIMARY KEY,
  session_id TEXT    NOT NULL,
  message_id TEXT    NOT NULL,
  name       TEXT    NOT NULL,
  args       TEXT    NOT NULL DEFAULT '{}',
  status     TEXT    NOT NULL DEFAULT 'pending',
  started_at INTEGER,
  ended_at   INTEGER,
  exit_code  INTEGER,
  preview    TEXT    NOT NULL DEFAULT '',
  blob_id    TEXT,
  tokens     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tool_msg ON tool_calls(message_id);
CREATE INDEX IF NOT EXISTS idx_tool_session ON tool_calls(session_id, started_at);

CREATE TABLE IF NOT EXISTS blobs(
  id         TEXT PRIMARY KEY,
  session_id TEXT,
  kind       TEXT    NOT NULL,
  label      TEXT    NOT NULL DEFAULT '',
  size       INTEGER NOT NULL,
  sha        TEXT    NOT NULL,
  created_at INTEGER NOT NULL,
  data       BLOB    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blob_session ON blobs(session_id, created_at);

CREATE TABLE IF NOT EXISTS digests(
  id         TEXT PRIMARY KEY,
  session_id TEXT    NOT NULL,
  version    INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  from_seq   INTEGER NOT NULL,
  to_seq     INTEGER NOT NULL,
  model      TEXT,
  tokens     INTEGER NOT NULL DEFAULT 0,
  body       TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_digest_session ON digests(session_id, version);

CREATE TABLE IF NOT EXISTS memory(
  scope      TEXT NOT NULL,
  key        TEXT NOT NULL,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(scope, key)
);

CREATE TABLE IF NOT EXISTS snapshots(
  id          TEXT PRIMARY KEY,
  session_id  TEXT    NOT NULL,
  tool_call_id TEXT,
  path        TEXT    NOT NULL,
  before_blob TEXT,
  after_blob  TEXT,
  created_at  INTEGER NOT NULL,
  reverted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_snap_session ON snapshots(session_id, created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
  text,
  role       UNINDEXED,
  session_id UNINDEXED,
  content='messages',
  content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, text, role, session_id)
  VALUES (new.rowid, new.text, new.role, new.session_id);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, text, role, session_id)
  VALUES ('delete', old.rowid, old.text, old.role, old.session_id);
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, text, role, session_id)
  VALUES ('delete', old.rowid, old.text, old.role, old.session_id);
  INSERT INTO messages_fts(rowid, text, role, session_id)
  VALUES (new.rowid, new.text, new.role, new.session_id);
END;
"#,
        )?;
        conn.execute(
            "INSERT OR REPLACE INTO meta(k, v) VALUES ('schema_version', ?1)",
            params![SCHEMA_VERSION.to_string()],
        )?;
        Ok(())
    }

    /// Single entry point. `payload` is JSON; the reply is JSON.
    pub fn call(&self, op: &str, payload: &str) -> Result<String> {
        let v: Value = if payload.trim().is_empty() {
            Value::Object(Map::new())
        } else {
            serde_json::from_str(payload)?
        };
        let out = match op {
            "session.create" => self.session_create(&v)?,
            "session.list" => self.session_list(&v)?,
            "session.get" => self.session_get(&v)?,
            "session.update" => self.session_update(&v)?,
            "session.delete" => self.session_delete(&v)?,
            "session.stats" => self.session_stats(&v)?,

            "message.append" => self.message_append(&v)?,
            "message.update" => self.message_update(&v)?,
            "message.window" => self.message_window(&v)?,
            "message.range" => self.message_range(&v)?,
            "message.archive" => self.message_archive(&v)?,
            "message.pin" => self.message_pin(&v)?,
            "message.rewind" => self.message_rewind(&v)?,

            "tool.record" => self.tool_record(&v)?,
            "tool.update" => self.tool_update(&v)?,
            "tool.list" => self.tool_list(&v)?,

            "blob.put" => self.blob_put(&v)?,
            "blob.read" => self.blob_read(&v)?,
            "blob.stat" => self.blob_stat(&v)?,

            "digest.save" => self.digest_save(&v)?,
            "digest.latest" => self.digest_latest(&v)?,
            "digest.list" => self.digest_list(&v)?,

            "memory.set" => self.memory_set(&v)?,
            "memory.get" => self.memory_get(&v)?,
            "memory.list" => self.memory_list(&v)?,
            "memory.delete" => self.memory_delete(&v)?,

            "snapshot.put" => self.snapshot_put(&v)?,
            "snapshot.list" => self.snapshot_list(&v)?,
            "snapshot.mark_reverted" => self.snapshot_mark_reverted(&v)?,

            "search.messages" => self.search_messages(&v)?,

            "maintenance.prune_blobs" => self.prune_blobs(&v)?,
            "maintenance.vacuum" => {
                self.conn.lock().execute_batch("VACUUM;")?;
                json!({ "ok": true })
            }

            other => return Err(CoreError::invalid(format!("unknown store op '{other}'"))),
        };
        Ok(out.to_string())
    }

    /* ---------------------------------------------------------------- sessions */

    fn session_create(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let now = now_ms();
        let conn = self.conn.lock();
        conn.execute(
            "INSERT INTO sessions(id, title, workspace, agent_id, endpoint_id, model,
                                  created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)",
            params![
                id,
                s_or(v, "title", ""),
                s_or(v, "workspace", ""),
                opt_s(v, "agentId"),
                opt_s(v, "endpointId"),
                opt_s(v, "model"),
                now
            ],
        )?;
        Ok(json!({ "id": id, "createdAt": now }))
    }

    fn session_list(&self, v: &Value) -> Result<Value> {
        let limit = i(v, "limit", 200);
        let include_archived = b(v, "includeArchived", false);
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, title, workspace, agent_id, endpoint_id, model, created_at,
                    updated_at, archived, next_seq, live_tokens, total_tokens, compactions,
                    (SELECT text FROM messages m WHERE m.session_id = s.id
                       AND m.role='user' ORDER BY m.seq DESC LIMIT 1) AS last_user
             FROM sessions s
             WHERE (?1 = 1 OR archived = 0)
             ORDER BY updated_at DESC
             LIMIT ?2",
        )?;
        let rows = stmt.query_map(params![include_archived as i64, limit], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "title": r.get::<_, String>(1)?,
                "workspace": r.get::<_, String>(2)?,
                "agentId": r.get::<_, Option<String>>(3)?,
                "endpointId": r.get::<_, Option<String>>(4)?,
                "model": r.get::<_, Option<String>>(5)?,
                "createdAt": r.get::<_, i64>(6)?,
                "updatedAt": r.get::<_, i64>(7)?,
                "archived": r.get::<_, i64>(8)? != 0,
                "nextSeq": r.get::<_, i64>(9)?,
                "liveTokens": r.get::<_, i64>(10)?,
                "totalTokens": r.get::<_, i64>(11)?,
                "compactions": r.get::<_, i64>(12)?,
                "preview": r.get::<_, Option<String>>(13)?.unwrap_or_default(),
            }))
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(json!({ "sessions": out }))
    }

    fn session_get(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        let row = conn
            .query_row(
                "SELECT title, workspace, agent_id, endpoint_id, model, created_at,
                        updated_at, archived, next_seq, live_tokens, total_tokens, compactions
                 FROM sessions WHERE id = ?1",
                params![id],
                |r| {
                    Ok(json!({
                        "id": id,
                        "title": r.get::<_, String>(0)?,
                        "workspace": r.get::<_, String>(1)?,
                        "agentId": r.get::<_, Option<String>>(2)?,
                        "endpointId": r.get::<_, Option<String>>(3)?,
                        "model": r.get::<_, Option<String>>(4)?,
                        "createdAt": r.get::<_, i64>(5)?,
                        "updatedAt": r.get::<_, i64>(6)?,
                        "archived": r.get::<_, i64>(7)? != 0,
                        "nextSeq": r.get::<_, i64>(8)?,
                        "liveTokens": r.get::<_, i64>(9)?,
                        "totalTokens": r.get::<_, i64>(10)?,
                        "compactions": r.get::<_, i64>(11)?,
                    }))
                },
            )
            .optional()?;
        row.ok_or_else(|| CoreError::not_found(format!("session {id}")))
    }

    fn session_update(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        let mut sets: Vec<&str> = Vec::new();
        let mut vals: Vec<Value> = Vec::new();
        for (field, column) in [
            ("title", "title"),
            ("workspace", "workspace"),
            ("agentId", "agent_id"),
            ("endpointId", "endpoint_id"),
            ("model", "model"),
        ] {
            if let Some(x) = v.get(field) {
                sets.push(column);
                vals.push(x.clone());
            }
        }
        let mut sql = String::from("UPDATE sessions SET updated_at = ?1");
        let mut binds: Vec<rusqlite::types::Value> =
            vec![rusqlite::types::Value::Integer(now_ms())];
        for (idx, col) in sets.iter().enumerate() {
            sql.push_str(&format!(", {col} = ?{}", idx + 2));
            binds.push(match &vals[idx] {
                Value::Null => rusqlite::types::Value::Null,
                Value::String(x) => rusqlite::types::Value::Text(x.clone()),
                other => rusqlite::types::Value::Text(other.to_string()),
            });
        }
        if let Some(a) = v.get("archived").and_then(Value::as_bool) {
            sql.push_str(&format!(", archived = ?{}", binds.len() + 1));
            binds.push(rusqlite::types::Value::Integer(a as i64));
        }
        sql.push_str(&format!(" WHERE id = ?{}", binds.len() + 1));
        binds.push(rusqlite::types::Value::Text(id.clone()));

        let n = conn.execute(&sql, params_from_iter(binds.iter()))?;
        Ok(json!({ "updated": n }))
    }

    fn session_delete(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        conn.execute("DELETE FROM blobs WHERE session_id = ?1", params![id])?;
        conn.execute("DELETE FROM tool_calls WHERE session_id = ?1", params![id])?;
        conn.execute("DELETE FROM digests WHERE session_id = ?1", params![id])?;
        conn.execute("DELETE FROM snapshots WHERE session_id = ?1", params![id])?;
        let n = conn.execute("DELETE FROM sessions WHERE id = ?1", params![id])?;
        Ok(json!({ "deleted": n }))
    }

    fn session_stats(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        let (live, archived, live_tokens, archived_tokens) = conn.query_row(
            "SELECT
               SUM(CASE WHEN archived = 0 THEN 1 ELSE 0 END),
               SUM(CASE WHEN archived = 1 THEN 1 ELSE 0 END),
               SUM(CASE WHEN archived = 0 THEN tokens ELSE 0 END),
               SUM(CASE WHEN archived = 1 THEN tokens ELSE 0 END)
             FROM messages WHERE session_id = ?1",
            params![id],
            |r| {
                Ok((
                    r.get::<_, Option<i64>>(0)?.unwrap_or(0),
                    r.get::<_, Option<i64>>(1)?.unwrap_or(0),
                    r.get::<_, Option<i64>>(2)?.unwrap_or(0),
                    r.get::<_, Option<i64>>(3)?.unwrap_or(0),
                ))
            },
        )?;
        let blob_bytes: i64 = conn
            .query_row(
                "SELECT COALESCE(SUM(size), 0) FROM blobs WHERE session_id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let digest_count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM digests WHERE session_id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let tool_count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM tool_calls WHERE session_id = ?1",
                params![id],
                |r| r.get(0),
            )
            .unwrap_or(0);
        Ok(json!({
            "liveMessages": live,
            "archivedMessages": archived,
            "liveTokens": live_tokens,
            "archivedTokens": archived_tokens,
            "blobBytes": blob_bytes,
            "digests": digest_count,
            "toolCalls": tool_count,
        }))
    }

    /* ---------------------------------------------------------------- messages */

    fn message_append(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let id = s(v, "id")?;
        let role = s(v, "role")?;
        let content = v
            .get("content")
            .map(|c| c.to_string())
            .unwrap_or_else(|| "[]".to_string());
        let text = s_or(v, "text", "");
        let tokens = i(v, "tokens", 0);
        let status = s_or(v, "status", "complete");
        let pinned = b(v, "pinned", false) as i64;
        let now = now_ms();

        let mut conn = self.conn.lock();
        let tx = conn.transaction()?;
        let seq: i64 = tx.query_row(
            "SELECT next_seq FROM sessions WHERE id = ?1",
            params![session_id],
            |r| r.get(0),
        )?;
        tx.execute(
            "INSERT INTO messages(id, session_id, seq, role, status, created_at,
                                  content, text, tokens, pinned)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            params![id, session_id, seq, role, status, now, content, text, tokens, pinned],
        )?;
        tx.execute(
            "UPDATE sessions
                SET next_seq = next_seq + 1,
                    updated_at = ?2,
                    live_tokens = live_tokens + ?3,
                    total_tokens = total_tokens + ?3
              WHERE id = ?1",
            params![session_id, now, tokens],
        )?;
        tx.commit()?;
        Ok(json!({ "id": id, "seq": seq, "createdAt": now }))
    }

    fn message_update(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let mut conn = self.conn.lock();
        let tx = conn.transaction()?;

        let (session_id, old_tokens, old_archived): (String, i64, i64) = tx
            .query_row(
                "SELECT session_id, tokens, archived FROM messages WHERE id = ?1",
                params![id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .map_err(|_| CoreError::not_found(format!("message {id}")))?;

        let new_tokens = i(v, "tokens", old_tokens);
        if let Some(c) = v.get("content") {
            tx.execute(
                "UPDATE messages SET content = ?2 WHERE id = ?1",
                params![id, c.to_string()],
            )?;
        }
        if let Some(t) = v.get("text").and_then(Value::as_str) {
            tx.execute("UPDATE messages SET text = ?2 WHERE id = ?1", params![id, t])?;
        }
        if let Some(st) = v.get("status").and_then(Value::as_str) {
            tx.execute(
                "UPDATE messages SET status = ?2 WHERE id = ?1",
                params![id, st],
            )?;
        }
        if new_tokens != old_tokens {
            tx.execute(
                "UPDATE messages SET tokens = ?2 WHERE id = ?1",
                params![id, new_tokens],
            )?;
            let delta = new_tokens - old_tokens;
            if old_archived == 0 {
                tx.execute(
                    "UPDATE sessions SET live_tokens = live_tokens + ?2,
                                         total_tokens = total_tokens + ?2,
                                         updated_at = ?3
                      WHERE id = ?1",
                    params![session_id, delta, now_ms()],
                )?;
            }
        }
        tx.commit()?;
        Ok(json!({ "ok": true, "tokens": new_tokens }))
    }

    /// The live window: pinned messages plus the tail, newest-last.
    fn message_window(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let limit = i(v, "limit", 400);
        let include_archived = b(v, "includeArchived", false);
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, seq, role, status, created_at, content, text, tokens, pinned, archived
             FROM messages
             WHERE session_id = ?1 AND (?2 = 1 OR archived = 0)
             ORDER BY seq DESC
             LIMIT ?3",
        )?;
        let rows = stmt.query_map(
            params![session_id, include_archived as i64, limit],
            Self::map_message,
        )?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        out.reverse();
        Ok(json!({ "messages": out }))
    }

    fn message_range(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let from = i(v, "fromSeq", 0);
        let to = i(v, "toSeq", i64::MAX);
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, seq, role, status, created_at, content, text, tokens, pinned, archived
             FROM messages
             WHERE session_id = ?1 AND seq >= ?2 AND seq <= ?3
             ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map(params![session_id, from, to], Self::map_message)?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "messages": out }))
    }

    fn map_message(r: &rusqlite::Row<'_>) -> rusqlite::Result<Value> {
        let content_raw: String = r.get(5)?;
        let content: Value = serde_json::from_str(&content_raw).unwrap_or(Value::Array(vec![]));
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "seq": r.get::<_, i64>(1)?,
            "role": r.get::<_, String>(2)?,
            "status": r.get::<_, String>(3)?,
            "createdAt": r.get::<_, i64>(4)?,
            "content": content,
            "text": r.get::<_, String>(6)?,
            "tokens": r.get::<_, i64>(7)?,
            "pinned": r.get::<_, i64>(8)? != 0,
            "archived": r.get::<_, i64>(9)? != 0,
        }))
    }

    /// Mark a seq range as archived and attribute it to a digest.
    ///
    /// Archiving never deletes: the text stays searchable through FTS and
    /// readable through `message.range`, which is what makes the
    /// `conversation_search` / `conversation_read` tools possible after
    /// compaction.
    fn message_archive(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let from = i(v, "fromSeq", 0);
        let to = i(v, "toSeq", -1);
        let digest_id = opt_s(v, "digestId");
        let keep_pinned = b(v, "keepPinned", true);

        let mut conn = self.conn.lock();
        let tx = conn.transaction()?;
        let freed: i64 = tx
            .query_row(
                "SELECT COALESCE(SUM(tokens), 0) FROM messages
                  WHERE session_id = ?1 AND seq >= ?2 AND seq <= ?3
                    AND archived = 0 AND (?4 = 0 OR pinned = 0)",
                params![session_id, from, to, keep_pinned as i64],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let n = tx.execute(
            "UPDATE messages SET archived = 1, digest_id = ?5
              WHERE session_id = ?1 AND seq >= ?2 AND seq <= ?3
                AND archived = 0 AND (?4 = 0 OR pinned = 0)",
            params![session_id, from, to, keep_pinned as i64, digest_id],
        )?;
        tx.execute(
            "UPDATE sessions
                SET live_tokens = MAX(0, live_tokens - ?2),
                    compactions = compactions + 1,
                    updated_at = ?3
              WHERE id = ?1",
            params![session_id, freed, now_ms()],
        )?;
        tx.commit()?;
        Ok(json!({ "archived": n, "tokensFreed": freed }))
    }

    fn message_pin(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let pinned = b(v, "pinned", true) as i64;
        let conn = self.conn.lock();
        let n = conn.execute(
            "UPDATE messages SET pinned = ?2 WHERE id = ?1",
            params![id, pinned],
        )?;
        Ok(json!({ "updated": n }))
    }

    /// Take a session back to the moment one of its messages was sent: a
    /// checkpoint.
    ///
    /// That message and everything after it go — the rows, their tool calls,
    /// the tool output spilled to blobs. So does every digest written since,
    /// and the messages those digests archived come back into the live
    /// window. What is left is the conversation as it stood, down to what the
    /// next request carries: the same digest, the same live messages, the
    /// same token count.
    ///
    /// Files are the caller's part. It restores them from the snapshots first
    /// and passes the ids of any it could not restore in `keepSnapshots`;
    /// every other snapshot taken since is dropped with the history it
    /// belonged to, and its blobs with it.
    ///
    /// Seqs continue from the message's own, so the next message takes its
    /// place — and a session rewound to its first message is named again by
    /// the next one, as a new session is.
    ///
    /// One transaction: a failure leaves the session as it was.
    fn message_rewind(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let message_id = s(v, "messageId")?;
        let keep: Vec<String> = v
            .get("keepSnapshots")
            .and_then(Value::as_array)
            .map(|ids| ids.iter().filter_map(Value::as_str).map(str::to_owned).collect())
            .unwrap_or_default();

        let mut conn = self.conn.lock();
        let tx = conn.transaction()?;
        let (from, since): (i64, i64) = tx
            .query_row(
                "SELECT seq, created_at FROM messages WHERE id = ?1 AND session_id = ?2",
                params![message_id, session_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?
            .ok_or_else(|| CoreError::not_found(format!("message {message_id}")))?;

        // Blobs that may be orphaned below. Collected first, deleted last, and
        // only when nothing left still points at them.
        let mut blobs: Vec<String> = Vec::new();
        {
            let mut stmt = tx.prepare(
                "SELECT t.blob_id FROM tool_calls t JOIN messages m ON m.id = t.message_id
                  WHERE m.session_id = ?1 AND m.seq >= ?2 AND t.blob_id IS NOT NULL",
            )?;
            for id in stmt.query_map(params![session_id, from], |r| r.get::<_, String>(0))? {
                blobs.push(id?);
            }
        }

        let tool_calls = tx.execute(
            "DELETE FROM tool_calls WHERE message_id IN
               (SELECT id FROM messages WHERE session_id = ?1 AND seq >= ?2)",
            params![session_id, from],
        )?;
        let messages = tx.execute(
            "DELETE FROM messages WHERE session_id = ?1 AND seq >= ?2",
            params![session_id, from],
        )?;

        // Compactions since the checkpoint. A digest is cumulative — each one
        // folds in the one before — so dropping the later ones leaves the
        // digest the checkpoint was sent with as the latest.
        let restored = tx.execute(
            "UPDATE messages SET archived = 0, digest_id = NULL
              WHERE session_id = ?1 AND archived = 1 AND digest_id IN
                (SELECT id FROM digests WHERE session_id = ?1 AND created_at >= ?2)",
            params![session_id, since],
        )?;
        let digests = tx.execute(
            "DELETE FROM digests WHERE session_id = ?1 AND created_at >= ?2",
            params![session_id, since],
        )?;

        let mut dropped = 0usize;
        {
            let mut stmt = tx.prepare(
                "SELECT id, before_blob, after_blob FROM snapshots
                  WHERE session_id = ?1 AND created_at >= ?2",
            )?;
            let rows: Vec<(String, Option<String>, Option<String>)> = stmt
                .query_map(params![session_id, since], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .collect::<rusqlite::Result<_>>()?;
            drop(stmt);
            for (id, before, after) in rows {
                if keep.contains(&id) {
                    continue;
                }
                tx.execute("DELETE FROM snapshots WHERE id = ?1", params![id])?;
                blobs.extend(before);
                blobs.extend(after);
                dropped += 1;
            }
        }

        for id in &blobs {
            tx.execute(
                "DELETE FROM blobs WHERE id = ?1
                   AND NOT EXISTS (SELECT 1 FROM tool_calls WHERE blob_id = ?1)
                   AND NOT EXISTS (SELECT 1 FROM snapshots WHERE before_blob = ?1 OR after_blob = ?1)",
                params![id],
            )?;
        }

        let live_tokens: i64 = tx.query_row(
            "SELECT COALESCE(SUM(tokens), 0) FROM messages WHERE session_id = ?1 AND archived = 0",
            params![session_id],
            |r| r.get(0),
        )?;
        tx.execute(
            "UPDATE sessions
                SET next_seq = ?2,
                    live_tokens = ?3,
                    total_tokens = (SELECT COALESCE(SUM(tokens), 0) FROM messages WHERE session_id = ?1),
                    compactions = MAX(0, compactions - ?4),
                    title = CASE WHEN ?2 = 0 THEN '' ELSE title END,
                    updated_at = ?5
              WHERE id = ?1",
            params![session_id, from, live_tokens, digests as i64, now_ms()],
        )?;
        tx.commit()?;
        Ok(json!({
            "messages": messages,
            "toolCalls": tool_calls,
            "digests": digests,
            "restored": restored,
            "snapshots": dropped,
            "liveTokens": live_tokens,
        }))
    }

    /* -------------------------------------------------------------- tool calls */

    fn tool_record(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT OR REPLACE INTO tool_calls(id, session_id, message_id, name, args,
                                               status, started_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![
                s(v, "id")?,
                s(v, "sessionId")?,
                s(v, "messageId")?,
                s(v, "name")?,
                v.get("args").map(|a| a.to_string()).unwrap_or("{}".into()),
                s_or(v, "status", "pending"),
                now_ms()
            ],
        )?;
        Ok(json!({ "ok": true }))
    }

    fn tool_update(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        let n = conn.execute(
            "UPDATE tool_calls
                SET status    = COALESCE(?2, status),
                    ended_at  = COALESCE(?3, ended_at),
                    exit_code = COALESCE(?4, exit_code),
                    preview   = COALESCE(?5, preview),
                    blob_id   = COALESCE(?6, blob_id),
                    tokens    = COALESCE(?7, tokens)
              WHERE id = ?1",
            params![
                id,
                opt_s(v, "status"),
                v.get("endedAt").and_then(Value::as_i64),
                v.get("exitCode").and_then(Value::as_i64),
                opt_s(v, "preview"),
                opt_s(v, "blobId"),
                v.get("tokens").and_then(Value::as_i64),
            ],
        )?;
        Ok(json!({ "updated": n }))
    }

    fn tool_list(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, message_id, name, args, status, started_at, ended_at,
                    exit_code, preview, blob_id, tokens
             FROM tool_calls WHERE session_id = ?1 ORDER BY started_at ASC",
        )?;
        let rows = stmt.query_map(params![session_id], |r| {
            let args_raw: String = r.get(3)?;
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "messageId": r.get::<_, String>(1)?,
                "name": r.get::<_, String>(2)?,
                "args": serde_json::from_str::<Value>(&args_raw).unwrap_or(Value::Null),
                "status": r.get::<_, String>(4)?,
                "startedAt": r.get::<_, Option<i64>>(5)?,
                "endedAt": r.get::<_, Option<i64>>(6)?,
                "exitCode": r.get::<_, Option<i64>>(7)?,
                "preview": r.get::<_, String>(8)?,
                "blobId": r.get::<_, Option<String>>(9)?,
                "tokens": r.get::<_, i64>(10)?,
            }))
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "toolCalls": out }))
    }

    /* ------------------------------------------------------------------ blobs */

    fn blob_put(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let data = s(v, "data")?;
        let bytes = data.as_bytes();
        let sha = blake3::hash(bytes).to_hex().to_string();
        let conn = self.conn.lock();
        conn.execute(
            "INSERT OR REPLACE INTO blobs(id, session_id, kind, label, size, sha, created_at, data)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
            params![
                id,
                opt_s(v, "sessionId"),
                s_or(v, "kind", "tool_output"),
                s_or(v, "label", ""),
                bytes.len() as i64,
                sha,
                now_ms(),
                bytes
            ],
        )?;
        Ok(json!({ "id": id, "size": bytes.len(), "sha": sha }))
    }

    /// Byte-range read, so the model can page through a 20 MB build log without
    /// anyone having to materialise it in full.
    fn blob_read(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let offset = i(v, "offset", 0).max(0) as usize;
        let length = i(v, "length", 64 * 1024).max(0) as usize;
        let conn = self.conn.lock();
        let (data, size): (Vec<u8>, i64) = conn
            .query_row(
                "SELECT data, size FROM blobs WHERE id = ?1",
                params![id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| CoreError::not_found(format!("blob {id}")))?;
        let end = (offset + length).min(data.len());
        let slice = if offset >= data.len() {
            &[][..]
        } else {
            &data[offset..end]
        };
        Ok(json!({
            "id": id,
            "offset": offset,
            "size": size,
            "eof": end >= data.len(),
            "text": String::from_utf8_lossy(slice),
        }))
    }

    fn blob_stat(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let conn = self.conn.lock();
        conn.query_row(
            "SELECT kind, label, size, sha, created_at FROM blobs WHERE id = ?1",
            params![id],
            |r| {
                Ok(json!({
                    "id": id,
                    "kind": r.get::<_, String>(0)?,
                    "label": r.get::<_, String>(1)?,
                    "size": r.get::<_, i64>(2)?,
                    "sha": r.get::<_, String>(3)?,
                    "createdAt": r.get::<_, i64>(4)?,
                }))
            },
        )
        .optional()?
        .ok_or_else(|| CoreError::not_found(format!("blob {id}")))
    }

    /// Reclaim blob storage, oldest first, down to `keepBytes`.
    ///
    /// Blobs are the largest disposable thing the store holds: spilled tool
    /// output, fetched pages, and a before/after pair for every file edit. On a
    /// long project they dominate the database, and none of them are needed
    /// once the session they belong to is finished.
    ///
    /// Blobs referenced by a tool call that is still pending or running are
    /// skipped — deleting one mid-flight would turn a truncated result into a
    /// dangling id the model cannot read.
    fn prune_blobs(&self, v: &Value) -> Result<Value> {
        let keep_bytes = i(v, "keepBytes", 256 * 1024 * 1024);
        let conn = self.conn.lock();
        let total_before: i64 = conn
            .query_row("SELECT COALESCE(SUM(size),0) FROM blobs", [], |r| r.get(0))
            .unwrap_or(0);
        if total_before <= keep_bytes {
            return Ok(json!({
                "pruned": 0,
                "bytesBefore": total_before,
                "bytesAfter": total_before,
                "bytesFreed": 0,
            }));
        }

        let mut stmt = conn.prepare(
            "SELECT id, size FROM blobs
              WHERE id NOT IN (SELECT blob_id FROM tool_calls
                                WHERE blob_id IS NOT NULL AND status IN ('running','pending'))
              ORDER BY created_at ASC",
        )?;
        let candidates: Vec<(String, i64)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .filter_map(std::result::Result::ok)
            .collect();
        drop(stmt);

        let mut remaining_to_free = total_before - keep_bytes;
        let mut pruned = 0i64;
        let mut freed = 0i64;
        for (id, size) in candidates {
            if remaining_to_free <= 0 {
                break;
            }
            conn.execute("DELETE FROM blobs WHERE id = ?1", params![id])?;
            remaining_to_free -= size;
            freed += size;
            pruned += 1;
        }

        Ok(json!({
            "pruned": pruned,
            "bytesBefore": total_before,
            "bytesAfter": total_before - freed,
            "bytesFreed": freed,
        }))
    }

    /* ---------------------------------------------------------------- digests */

    fn digest_save(&self, v: &Value) -> Result<Value> {
        let id = s(v, "id")?;
        let session_id = s(v, "sessionId")?;
        let body = v
            .get("body")
            .map(|x| x.to_string())
            .ok_or_else(|| CoreError::invalid("missing 'body'"))?;
        let conn = self.conn.lock();
        let version: i64 = conn
            .query_row(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM digests WHERE session_id = ?1",
                params![session_id],
                |r| r.get(0),
            )
            .unwrap_or(1);
        conn.execute(
            "INSERT OR REPLACE INTO digests(id, session_id, version, created_at,
                                            from_seq, to_seq, model, tokens, body)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            params![
                id,
                session_id,
                version,
                now_ms(),
                i(v, "fromSeq", 0),
                i(v, "toSeq", 0),
                opt_s(v, "model"),
                i(v, "tokens", 0),
                body
            ],
        )?;
        Ok(json!({ "id": id, "version": version }))
    }

    fn digest_latest(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let conn = self.conn.lock();
        let row = conn
            .query_row(
                "SELECT id, version, created_at, from_seq, to_seq, model, tokens, body
                 FROM digests WHERE session_id = ?1 ORDER BY version DESC LIMIT 1",
                params![session_id],
                |r| {
                    let body_raw: String = r.get(7)?;
                    Ok(json!({
                        "id": r.get::<_, String>(0)?,
                        "version": r.get::<_, i64>(1)?,
                        "createdAt": r.get::<_, i64>(2)?,
                        "fromSeq": r.get::<_, i64>(3)?,
                        "toSeq": r.get::<_, i64>(4)?,
                        "model": r.get::<_, Option<String>>(5)?,
                        "tokens": r.get::<_, i64>(6)?,
                        "body": serde_json::from_str::<Value>(&body_raw).unwrap_or(Value::Null),
                    }))
                },
            )
            .optional()?;
        // An empty object, not null, for a session that was never compacted:
        // every reply from `call` is an object, and the Kotlin side reads a
        // missing "body" as "no digest". A bare null here failed the first
        // turn of every new session.
        Ok(row.unwrap_or_else(|| json!({})))
    }

    /// Every digest of a session, oldest first, each with its body: the chat
    /// draws a seam where each one folded the history, with the summary it
    /// wrote. A body is a few kilobytes and a session has a handful.
    fn digest_list(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, version, created_at, from_seq, to_seq, tokens, body
             FROM digests WHERE session_id = ?1 ORDER BY version ASC",
        )?;
        let rows = stmt.query_map(params![session_id], |r| {
            let body_raw: String = r.get(6)?;
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "version": r.get::<_, i64>(1)?,
                "createdAt": r.get::<_, i64>(2)?,
                "fromSeq": r.get::<_, i64>(3)?,
                "toSeq": r.get::<_, i64>(4)?,
                "tokens": r.get::<_, i64>(5)?,
                "body": serde_json::from_str::<Value>(&body_raw).unwrap_or(Value::Null),
            }))
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "digests": out }))
    }

    /* ----------------------------------------------------------------- memory */

    fn memory_set(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT INTO memory(scope, key, value, updated_at) VALUES (?1,?2,?3,?4)
             ON CONFLICT(scope, key) DO UPDATE SET value = ?3, updated_at = ?4",
            params![s(v, "scope")?, s(v, "key")?, s(v, "value")?, now_ms()],
        )?;
        Ok(json!({ "ok": true }))
    }

    fn memory_get(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        let value: Option<String> = conn
            .query_row(
                "SELECT value FROM memory WHERE scope = ?1 AND key = ?2",
                params![s(v, "scope")?, s(v, "key")?],
                |r| r.get(0),
            )
            .optional()?;
        Ok(json!({ "value": value }))
    }

    fn memory_list(&self, v: &Value) -> Result<Value> {
        let scope = s(v, "scope")?;
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT key, value, updated_at FROM memory WHERE scope = ?1 ORDER BY key ASC",
        )?;
        let rows = stmt.query_map(params![scope], |r| {
            Ok(json!({
                "key": r.get::<_, String>(0)?,
                "value": r.get::<_, String>(1)?,
                "updatedAt": r.get::<_, i64>(2)?,
            }))
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "entries": out }))
    }

    fn memory_delete(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        let n = conn.execute(
            "DELETE FROM memory WHERE scope = ?1 AND key = ?2",
            params![s(v, "scope")?, s(v, "key")?],
        )?;
        Ok(json!({ "deleted": n }))
    }

    /* -------------------------------------------------------------- snapshots */

    fn snapshot_put(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT OR REPLACE INTO snapshots(id, session_id, tool_call_id, path,
                                              before_blob, after_blob, created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7)",
            params![
                s(v, "id")?,
                s(v, "sessionId")?,
                opt_s(v, "toolCallId"),
                s(v, "path")?,
                opt_s(v, "beforeBlob"),
                opt_s(v, "afterBlob"),
                now_ms()
            ],
        )?;
        Ok(json!({ "ok": true }))
    }

    fn snapshot_list(&self, v: &Value) -> Result<Value> {
        let session_id = s(v, "sessionId")?;
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, tool_call_id, path, before_blob, after_blob, created_at, reverted
             FROM snapshots WHERE session_id = ?1 ORDER BY created_at DESC",
        )?;
        let rows = stmt.query_map(params![session_id], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "toolCallId": r.get::<_, Option<String>>(1)?,
                "path": r.get::<_, String>(2)?,
                "beforeBlob": r.get::<_, Option<String>>(3)?,
                "afterBlob": r.get::<_, Option<String>>(4)?,
                "createdAt": r.get::<_, i64>(5)?,
                "reverted": r.get::<_, i64>(6)? != 0,
            }))
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "snapshots": out }))
    }

    fn snapshot_mark_reverted(&self, v: &Value) -> Result<Value> {
        let conn = self.conn.lock();
        let n = conn.execute(
            "UPDATE snapshots SET reverted = 1 WHERE id = ?1",
            params![s(v, "id")?],
        )?;
        Ok(json!({ "updated": n }))
    }

    /* ----------------------------------------------------------------- search */

    /// Full-text search over message bodies.
    ///
    /// This is what lets a compacted session still answer "what did we decide
    /// about the retry policy": the digest holds the summary, FTS holds the
    /// verbatim history, and the recall step injects the top hits back into the
    /// prompt.
    fn search_messages(&self, v: &Value) -> Result<Value> {
        let query = s(v, "query")?;
        let limit = i(v, "limit", 20);
        let session_id = opt_s(v, "sessionId");
        let archived_only = b(v, "archivedOnly", false);

        let fts_query = sanitise_fts(&query);
        if fts_query.is_empty() {
            return Ok(json!({ "hits": [] }));
        }

        let conn = self.conn.lock();
        let sql = "
            SELECT m.id, m.session_id, m.seq, m.role, m.created_at, m.archived,
                   snippet(messages_fts, 0, '<<', '>>', ' … ', 24) AS snip,
                   bm25(messages_fts) AS score
              FROM messages_fts
              JOIN messages m ON m.rowid = messages_fts.rowid
             WHERE messages_fts MATCH ?1
               AND (?2 IS NULL OR m.session_id = ?2)
               AND (?3 = 0 OR m.archived = 1)
             ORDER BY score
             LIMIT ?4";
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt.query_map(
            params![fts_query, session_id, archived_only as i64, limit],
            |r| {
                Ok(json!({
                    "messageId": r.get::<_, String>(0)?,
                    "sessionId": r.get::<_, String>(1)?,
                    "seq": r.get::<_, i64>(2)?,
                    "role": r.get::<_, String>(3)?,
                    "createdAt": r.get::<_, i64>(4)?,
                    "archived": r.get::<_, i64>(5)? != 0,
                    "snippet": r.get::<_, String>(6)?,
                    "score": r.get::<_, f64>(7)?,
                }))
            },
        )?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(json!({ "hits": out }))
    }
}

/// FTS5 MATCH syntax is a minefield of operators. User and model queries are
/// free text, so quote every term and OR them together.
fn sanitise_fts(q: &str) -> String {
    q.split(|c: char| !c.is_alphanumeric() && c != '_' && c != '-')
        .filter(|t| t.len() >= 2)
        .take(12)
        .map(|t| format!("\"{}\"", t.replace('"', "")))
        .collect::<Vec<_>>()
        .join(" OR ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> Store {
        Store::open(":memory:").unwrap()
    }

    #[test]
    fn session_and_message_roundtrip() {
        let st = store();
        st.call("session.create", &json!({"id":"s1","title":"t"}).to_string())
            .unwrap();
        st.call(
            "message.append",
            &json!({
                "sessionId":"s1","id":"m1","role":"user",
                "content":[{"type":"text","text":"hello retry policy"}],
                "text":"hello retry policy","tokens":5
            })
            .to_string(),
        )
        .unwrap();

        let win: Value =
            serde_json::from_str(&st.call("message.window", &json!({"sessionId":"s1"}).to_string()).unwrap())
                .unwrap();
        assert_eq!(win["messages"].as_array().unwrap().len(), 1);

        let hits: Value = serde_json::from_str(
            &st.call("search.messages", &json!({"query":"retry policy"}).to_string())
                .unwrap(),
        )
        .unwrap();
        assert_eq!(hits["hits"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn archiving_frees_live_tokens_but_keeps_text() {
        let st = store();
        st.call("session.create", &json!({"id":"s1"}).to_string()).unwrap();
        for n in 0..5 {
            st.call(
                "message.append",
                &json!({"sessionId":"s1","id":format!("m{n}"),"role":"user",
                        "text":format!("msg {n}"),"tokens":100})
                .to_string(),
            )
            .unwrap();
        }
        let res: Value = serde_json::from_str(
            &st.call(
                "message.archive",
                &json!({"sessionId":"s1","fromSeq":0,"toSeq":2,"digestId":"d1"}).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(res["archived"], 3);
        assert_eq!(res["tokensFreed"], 300);

        let stats: Value = serde_json::from_str(
            &st.call("session.stats", &json!({"id":"s1"}).to_string()).unwrap(),
        )
        .unwrap();
        assert_eq!(stats["liveMessages"], 2);
        assert_eq!(stats["archivedMessages"], 3);
        // Archived text is still indexed.
        let hits: Value = serde_json::from_str(
            &st.call(
                "search.messages",
                &json!({"query":"msg","archivedOnly":true}).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(hits["hits"].as_array().unwrap().len(), 3);
    }

    #[test]
    fn blob_paging() {
        let st = store();
        let body = "x".repeat(5000);
        st.call("blob.put", &json!({"id":"b1","data":body}).to_string()).unwrap();
        let page: Value = serde_json::from_str(
            &st.call("blob.read", &json!({"id":"b1","offset":4990,"length":100}).to_string())
                .unwrap(),
        )
        .unwrap();
        assert_eq!(page["text"].as_str().unwrap().len(), 10);
        assert_eq!(page["eof"], true);
    }

    /// The Kotlin side requires an object from every op. A session that was
    /// never compacted has no digest, and that answer used to be a bare
    /// `null`, which failed the first turn of every new session on the device.
    #[test]
    fn latest_digest_of_an_uncompacted_session_is_an_empty_object() {
        let st = store();
        st.call("session.create", &json!({"id":"s1"}).to_string()).unwrap();
        let reply = st
            .call("digest.latest", &json!({"sessionId":"s1"}).to_string())
            .unwrap();
        let value: Value = serde_json::from_str(&reply).unwrap();
        assert!(value.is_object(), "expected an object, got {reply}");
        assert!(value.get("body").is_none());
    }

    fn call(st: &Store, op: &str, payload: Value) -> Value {
        serde_json::from_str(&st.call(op, &payload.to_string()).unwrap()).unwrap()
    }

    fn append(st: &Store, id: &str, role: &str) -> Value {
        call(
            st,
            "message.append",
            json!({"sessionId":"s1","id":id,"role":role,"text":format!("text of {id}"),"tokens":100}),
        )
    }

    /// A checkpoint puts the session back as it was when its message was
    /// sent: what came after is gone, a compaction made since is undone, a
    /// compaction made before stays, and the next message takes the seq.
    #[test]
    fn rewind_returns_the_session_to_when_the_message_was_sent() {
        let st = store();
        call(&st, "session.create", json!({"id":"s1","title":"first words"}));
        append(&st, "m0", "user");
        append(&st, "m1", "assistant");
        call(&st, "digest.save", json!({"id":"d1","sessionId":"s1","fromSeq":0,"toSeq":0,"body":{}}));
        call(&st, "message.archive", json!({"sessionId":"s1","fromSeq":0,"toSeq":0,"digestId":"d1"}));
        append(&st, "m2", "user");
        append(&st, "m3", "assistant");
        call(&st, "tool.record", json!({"id":"t1","sessionId":"s1","messageId":"m3","name":"fs_write"}));
        call(&st, "blob.put", json!({"id":"b-out","sessionId":"s1","data":"output"}));
        call(&st, "tool.update", json!({"id":"t1","blobId":"b-out"}));
        call(&st, "blob.put", json!({"id":"b-before","sessionId":"s1","data":"old text"}));
        call(&st, "snapshot.put", json!({"id":"p1","sessionId":"s1","path":"/a","beforeBlob":"b-before"}));
        call(&st, "snapshot.put", json!({"id":"p2","sessionId":"s1","path":"/b"}));
        call(&st, "digest.save", json!({"id":"d2","sessionId":"s1","fromSeq":0,"toSeq":1,"body":{}}));
        call(&st, "message.archive", json!({"sessionId":"s1","fromSeq":1,"toSeq":1,"digestId":"d2"}));

        // All of the above ran inside a millisecond or two; give it the order
        // a real session has: m0 1000, m1 1010, d1 1015, m2 1020, m3 1030,
        // snapshots 1031, d2 1035.
        {
            let conn = st.conn.lock();
            conn.execute("UPDATE messages SET created_at = 1000 + seq * 10", []).unwrap();
            conn.execute("UPDATE digests SET created_at = CASE id WHEN 'd1' THEN 1015 ELSE 1035 END", [])
                .unwrap();
            conn.execute("UPDATE snapshots SET created_at = 1031", []).unwrap();
        }

        let res = call(
            &st,
            "message.rewind",
            json!({"sessionId":"s1","messageId":"m2","keepSnapshots":["p2"]}),
        );
        assert_eq!(res["messages"], 2);
        assert_eq!(res["toolCalls"], 1);
        assert_eq!(res["digests"], 1);
        assert_eq!(res["restored"], 1);
        assert_eq!(res["snapshots"], 1);
        assert_eq!(res["liveTokens"], 100);

        // m0 stays under the earlier digest; m1 is live again.
        let window = call(&st, "message.window", json!({"sessionId":"s1"}));
        let ids: Vec<&str> = window["messages"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids, ["m1"]);
        assert_eq!(call(&st, "digest.latest", json!({"sessionId":"s1"}))["id"], "d1");

        // The dropped call's output and the dropped snapshot's blob are gone;
        // the snapshot the caller kept is not.
        assert!(st.call("blob.stat", &json!({"id":"b-out"}).to_string()).is_err());
        assert!(st.call("blob.stat", &json!({"id":"b-before"}).to_string()).is_err());
        let snapshots = call(&st, "snapshot.list", json!({"sessionId":"s1"}));
        assert_eq!(snapshots["snapshots"].as_array().unwrap().len(), 1);
        assert_eq!(snapshots["snapshots"][0]["id"], "p2");
        assert!(call(&st, "tool.list", json!({"sessionId":"s1"}))["toolCalls"]
            .as_array()
            .unwrap()
            .is_empty());

        // Deleted text leaves the index too.
        let hits = call(&st, "search.messages", json!({"query":"m3","sessionId":"s1"}));
        assert!(hits["hits"].as_array().unwrap().is_empty());

        let session = call(&st, "session.get", json!({"id":"s1"}));
        assert_eq!(session["nextSeq"], 2);
        assert_eq!(session["liveTokens"], 100);
        assert_eq!(session["totalTokens"], 200);
        assert_eq!(session["compactions"], 1);
        assert_eq!(session["title"], "first words");

        assert_eq!(append(&st, "m4", "user")["seq"], 2);
    }

    /// Back to the first message is back to a new session, title included:
    /// the next first message names it again.
    #[test]
    fn rewinding_to_the_first_message_empties_the_session() {
        let st = store();
        call(&st, "session.create", json!({"id":"s1","title":"first words"}));
        append(&st, "m0", "user");
        append(&st, "m1", "assistant");

        let res = call(&st, "message.rewind", json!({"sessionId":"s1","messageId":"m0"}));
        assert_eq!(res["messages"], 2);

        let session = call(&st, "session.get", json!({"id":"s1"}));
        assert_eq!(session["title"], "");
        assert_eq!(session["nextSeq"], 0);
        assert_eq!(session["liveTokens"], 0);
        assert_eq!(append(&st, "m2", "user")["seq"], 0);
    }

    #[test]
    fn rewind_to_a_message_of_another_session_is_refused() {
        let st = store();
        call(&st, "session.create", json!({"id":"s1"}));
        call(&st, "session.create", json!({"id":"s2"}));
        append(&st, "m0", "user");
        assert!(st
            .call("message.rewind", &json!({"sessionId":"s2","messageId":"m0"}).to_string())
            .is_err());
        assert_eq!(call(&st, "session.get", json!({"id":"s1"}))["nextSeq"], 1);
    }
}
