use std::fmt;

#[derive(Debug)]
pub enum CoreError {
    /// Caller passed something structurally wrong (bad path, bad JSON, bad id).
    Invalid(String),
    /// Requested thing is not there.
    NotFound(String),
    /// The operation is understood but refused by policy (sandbox escape,
    /// oversized payload, binary file where text was expected).
    Refused(String),
    Io(std::io::Error),
    Sqlite(String),
    Json(String),
    /// A syscall failed; carries errno so the Kotlin side can map EACCES to a
    /// "this is the SELinux W^X rule" explanation instead of a generic failure.
    Errno { call: &'static str, errno: i32 },
    Internal(String),
}

impl CoreError {
    pub fn invalid(m: impl Into<String>) -> Self {
        CoreError::Invalid(m.into())
    }
    pub fn not_found(m: impl Into<String>) -> Self {
        CoreError::NotFound(m.into())
    }
    pub fn refused(m: impl Into<String>) -> Self {
        CoreError::Refused(m.into())
    }
    pub fn internal(m: impl Into<String>) -> Self {
        CoreError::Internal(m.into())
    }

    pub fn last_errno(call: &'static str) -> Self {
        CoreError::Errno {
            call,
            errno: std::io::Error::last_os_error().raw_os_error().unwrap_or(0),
        }
    }

    /// Stable machine-readable kind, surfaced to Kotlin and ultimately to the
    /// model so it can decide whether retrying makes sense.
    pub fn kind(&self) -> &'static str {
        match self {
            CoreError::Invalid(_) => "invalid",
            CoreError::NotFound(_) => "not_found",
            CoreError::Refused(_) => "refused",
            CoreError::Io(_) => "io",
            CoreError::Sqlite(_) => "sqlite",
            CoreError::Json(_) => "json",
            CoreError::Errno { .. } => "errno",
            CoreError::Internal(_) => "internal",
        }
    }
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CoreError::Invalid(m) => write!(f, "invalid: {m}"),
            CoreError::NotFound(m) => write!(f, "not found: {m}"),
            CoreError::Refused(m) => write!(f, "refused: {m}"),
            CoreError::Io(e) => write!(f, "io: {e}"),
            CoreError::Sqlite(m) => write!(f, "sqlite: {m}"),
            CoreError::Json(m) => write!(f, "json: {m}"),
            CoreError::Errno { call, errno } => {
                let hint = match *errno {
                    libc::EACCES => " (EACCES — on targetSdk>=29 this is usually the \
                                      W^X rule: the binary must live in nativeLibraryDir)",
                    libc::ENOENT => " (ENOENT — missing interpreter? Android has no /bin/sh)",
                    libc::ENOEXEC => " (ENOEXEC — wrong ABI or a glibc-linked ELF)",
                    _ => "",
                };
                write!(f, "{call} failed: errno {errno}{hint}")
            }
            CoreError::Internal(m) => write!(f, "internal: {m}"),
        }
    }
}

impl std::error::Error for CoreError {}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io(e)
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        CoreError::Sqlite(e.to_string())
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Json(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;
