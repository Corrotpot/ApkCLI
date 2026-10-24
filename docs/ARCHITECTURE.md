# Architecture

**English** · [Русский](ru/ARCHITECTURE.md)

Why the pieces are shaped the way they are. For the exec restriction that
dictates most of the runtime layer, see `ANDROID_EXEC_NOTES.md`.

## Two Gradle modules

`:app` is Compose UI. `:core` is everything else and does not depend on Compose.

Ten feature modules were considered and rejected: the build-file overhead and
cross-module visibility dance cost more than package boundaries do, at this
size. The one hard rule is that `:core` never imports anything from
`androidx.compose` — which is why `UiBridge` is an interface the app implements
rather than a direct call into a ViewModel.

## Rust, not C++

Both were viable. Rust won on three specific points:

* **Cross-compilation.** `cargo-ndk` plus `rustup target add` is the whole
  setup. CMake toolchain files for four ABIs are not.
* **The libraries already exist.** ripgrep's engine (`grep-searcher`,
  `grep-regex`, `ignore`) gives a complete, battle-tested workspace search as a
  dependency. `rusqlite` with `bundled` compiles SQLite from source with FTS5
  enabled, so there is no reliance on whatever `libsqlite3` the device happens
  to have.
* **The risky code is unsafe-local.** The `fork()`/`execve()` sequence and the
  ioctl work are in explicitly `unsafe` blocks; everything else is checked.

`panic = "unwind"` in the release profile is load-bearing, not an oversight: the
JNI layer catches panics and rethrows them as Java exceptions. With `abort`, one
bad index in the search engine would take the whole app down.

### What is in Rust and why

| Module | Reason |
| --- | --- |
| `store` | A million-token session is tens of megabytes of text. In a JVM object graph that is a multi-second cold start and a heap the low-memory killer notices. |
| `search` | ripgrep's engine as a library. No process spawn, no output parsing, and it works on the Play flavour where a downloaded `rg` could not be executed. |
| `diff` | Three-strategy forgiving match (exact, trailing-whitespace-insensitive, reindented). Hot enough to matter, and the logic is testable on a host. |
| `tokens` | Called on every redraw of the context meter and for every tool result. A real BPE pass over a megabyte on each is not viable. |
| `pty` | Needs `fork`, `setsid`, `TIOCSCTTY` — none of which the JVM exposes. |
| `sched` | CPU topology from `/sys`, `sched_setaffinity`, `setpriority`. |

### One FFI entry point per module

Every native call is `(op, payloadJson) -> resultJson`. Crossing JNI costs
about a microsecond, which is irrelevant next to the SQLite or search work, and
it means adding a capability touches one Rust match arm instead of a new
`external fun`, a new ProGuard keep rule, and a signature mismatch waiting to
happen.

## Three directories

```
/data/data/dev.apkcli/files/
├── home/   $HOME    user's own. Projects, dotfiles. Backed up.
├── usr/    $PREFIX  toolchain. Rebuilt from the APK — safe to delete.
└── var/             database, logs, index. Disposable.
```

The split is functional, not cosmetic:

* Nothing in `home/` is generated, so it is the only thing worth backing up.
* `usr/` is fully reconstructible by `Bootstrap`, which makes deleting it a
  supported repair action.
* `var/` can be deleted to reclaim space. It is kept out of `home/` precisely so
  that "clear caches" can never touch user code.

Nothing uses `context.cacheDir`. The system deletes it under storage pressure,
which would silently destroy a conversation database.

## The load governor

The requirement: the editor stays smooth while a build, an index pass and a
streaming completion are all in flight.

Work is admitted by class — the distinction is *who is waiting*, not how
expensive the work is:

| Class | Who waits | Treatment |
| --- | --- | --- |
| Interactive | A human watching a spinner | Big cluster, default priority, never starved |
| Background | The agent, on heavy work: commands, builds, packages, fetches | Whole machine, niced down |
| Bulk | Nobody asked | Pinned to the little cluster, niced hard, first to be dropped |

Quick work the user watches is interactive even when the agent asked for it:
file writes and edits, moves, deletes, reverts, memory and the store writes on
a turn's path. They used to be background, and Android keeps background
threads in a cgroup with a small CPU share, so with a build running a write
that takes two milliseconds waited seconds for a turn on a core.

Three mechanisms, in increasing bluntness:

1. **Separate pools**, with `nice` and `sched_setaffinity` applied on the worker
   threads themselves. An indexing pass physically cannot contend with the UI
   for a big core.
2. **Admission control.** A semaphore per class, sized by the current policy.
   Resizing swaps the semaphore rather than resizing the pool — threads are
   expensive to churn, and in-flight work keeps its old permit and drains.
   The semaphores limit concurrency, not the pools: the interactive and
   background pools grow on demand (up to 64 threads, idle ones retire after
   30 s), and `admit` runs the block detached and awaits it, so a caller that
   gives up gets its permit back at once. With fixed pools, every call that
   never returned took a thread for good; two hung commands were enough to
   queue every later tool and store write behind them.
3. **Refusal.** Above a thermal threshold, builds and indexing are *declined*,
   not slowed. A compile on a thermally saturated phone takes longer **and**
   makes everything else unusable; starting it is the wrong trade. The refusal
   explains itself to both the user and the model.

Policy inputs: thermal status, power-save mode, battery level, charging state,
screen state. They are collapsed into one pressure level first, because handling
them separately produced policies that contradicted each other.

## Process isolation — partially done

Currently: one process, plus a foreground service to hold priority during a
turn.

The foreground service is the part that was actually needed. Without it,
backgrounding the app mid-turn means the process is frozen or killed and the
request that was already paid for is lost. Reading documentation in a browser
while the agent works is normal phone behaviour, not an edge case.

A `:shell` process for PTY work is **not** built, and the reasoning is worth
recording because it is tempting:

*For it.* `PtySession` calls `fork()` from a thread of a running JVM. That is
necessary — `std::process` cannot make the child a session leader of a new
controlling terminal — and it is the riskiest code here. Isolation would mean a
bad fork or a runaway child takes down the terminal instead of the editor and the
conversation. A separate process also gets its own heap budget, which matters
when a build log is hundreds of megabytes.

*Against it, for now.* The data path must stay out of binder. Marshalling
terminal bytes through IPC per chunk would be worse than the problem it solves.
The correct design is:

1. A new native op that `dup()`s the PTY master and returns the raw fd.
2. `ParcelFileDescriptor.adoptFd` to hand it across binder, so the caller does
   its own `read`/`write` on a real fd.
3. Only `open`, `resize`, `kill` and `close` cross the boundary.

That needs the native op, an AIDL interface, and a second read path in
`PtySession`. Until it exists, the manifest records why rather than declaring a
service that is not implemented.

Worth being clear about what process separation buys: **isolation and heap, not
latency.** The kernel schedules threads, not processes. The load governor is
what keeps the UI smooth.

## Context management

The problem with a million-token window is not fitting into it — it is that the
naive approach fails in three specific ways:

1. The failure arrives as a 400 *after* the user waited, and the turn is lost.
2. Cost grows quadratically. Every turn re-sends the whole history.
3. Attention degrades well before the hard limit.

### Budget

`ContextBudget` accounts the prompt by component — system, tool schemas, digest,
project memory, live messages — because the breakdown is what tells you what to
do. "60% used" prompts a shrug; "60% used, of which 40% is one build log" prompts
an action. The breakdown is in the UI for the same reason.

Soft threshold (75%) starts a digest in the background while the user reads the
previous answer. Hard threshold (90%) compacts before the next request.

### Compaction produces a schema, not a paragraph

A free-form "summary of the conversation so far" loses exactly what matters:
which file held the bug, which approach already failed, what the user said not
to do. The agent then re-explores dead ends, costing more than the summary
saved.

So `ConversationDigest` is a structured object the model fills in through a
**forced tool call**. The highest-value field is `failedApproaches` — without it
a compacted agent confidently retries a known dead end, which is the single most
expensive failure mode in a long session.

Compaction is **cumulative**: each pass merges into the previous digest rather
than re-summarising from scratch. Repeated lossy passes over the same text is
how detail evaporates.

### Nothing is deleted

Archiving sets a flag. The rows stay in SQLite and stay indexed for FTS. That is
what makes `conversation_search` and `conversation_read` work, and it turns
compaction from amnesia into a lookup. The system prompt tells the model this
explicitly, with instructions to search before concluding something was never
discussed.

A recall step also injects the handful of archived messages matching the current
request, which is how a compacted session can still quote an exact error from
two hundred turns ago.

### Splitting by turn, not by message

`planCompaction` splits on user-message boundaries. Archiving an assistant
message while keeping the tool results that answer it leaves orphaned `tool`
messages referencing a call the provider cannot see — a hard 400 on both
protocols.

### Counted whole, sent whole

* **The window is every live message.** `store.window` stopped at the newest
  400 messages for the chat and 600 for a request. Past that the oldest live
  turns left the screen and the request without a word, and a cut that fell
  between a call and its result was a request the provider refused. It
  returns them all now: what keeps the window to the budget is compaction,
  by tokens, not a count of messages.
* **Tool results count whole.** A message's tokens were estimated from
  `Message.flatten()`, the search index's text, which keeps 400 characters of
  each tool result. A 16 000-character build log counted as about a hundred
  tokens, a chat of tool calls read as a fraction of its size, and compaction
  could come only after the provider had refused the request.
  `Message.promptText()` is what a request carries, results whole, and the
  store counts from it. Counts stored by earlier builds are corrected the
  first time a turn reads them (`AgentLoop.countedWhole`).
* **Every call is answered.** A turn stopped while its calls run, or while the
  model is still writing them, leaves calls with no result, and both protocols
  refuse a request that carries one, so a chat stopped mid-call could not go
  on with those providers. `Transcript.answered` gives such a call a result in
  the request (it did not finish: check its effect before repeating it) and
  leaves out a result whose call is not there. The stored chat is not changed,
  and the card still says stopped. Kiro's wire already paired calls on its own
  (`KiroWire.canonical`).

## Token estimation with self-calibration

A character-class estimator, not a BPE implementation. Within roughly ±8% on
mixed English, Russian and source code — and the residual error is removed by
feeding the provider's authoritative `usage.prompt_tokens` back in after every
completion. After two or three turns the meter tracks the provider closely,
which is what makes budgeting on an estimate safe.

Russian costs about twice as much per character as English. Ignoring that
underestimates a Russian conversation by nearly half, so the Kotlin fallback
accounts for it too.

## Model metadata is read, never hardcoded

An earlier version had a table mapping model names to context windows. It was
stale before it was finished — the live OpenRouter catalogue now contains models
that did not exist when the table was written.

`ModelDiscovery` reads what endpoints actually publish:
`top_provider.context_length` (preferred over the root `context_length`, which
is the model's theoretical maximum rather than what the serving provider
allows), `max_model_len` for vLLM, `max_context_length` for LM Studio,
`supported_parameters` for the reasoning dialect, `architecture.input_modalities`
for vision.

Endpoints that report nothing get a conservative fallback flagged
`metadataFromEndpoint = false`, shown as "context ≈…" in the model sheet. And
the first overflow error states the real limit — `Http.extractContextLimit`
parses it and settings persists it. The app learns instead of guessing.

## LLM layer

Three protocols, three adapters: OpenAI Chat Completions, Anthropic Messages,
and Kiro (`KiroAdapter`), which speaks Amazon Q's `generateAssistantResponse`
and reads an AWS event-stream (`AwsEventStream`) rather than SSE. What each
service needs is in `docs/PROVIDERS.md`.

Anthropic Messages is separate rather than translated because reasoning there
returns `thinking` blocks with an opaque `signature` that **must** be echoed
back verbatim on the next turn. Drop it and the first turn succeeds while the
second fails — a failure that is very hard to attribute if the signature was
never stored. That requirement is why `Block.Thinking` has a `signature` field
at all. The same applies to `reasoning_details` when a Claude model is reached
through OpenRouter.

`OPENAI_RESPONSES` was removed from the protocol enum. There was no adapter for
it, and a selectable protocol that cannot work is worse than an absent one.

### Hand-rolled SSE

Not OkHttp's `EventSource`, for reasons that come from real endpoints rather than
the spec:

* `EventSource` wants to own reconnection. Reconnecting a half-finished
  completion gives a duplicate partial answer, billed twice.
* Several gateways emit `data:` with no `event:`, some emit bare JSON lines, and
  `[DONE]` spacing varies. A strict parser rejects streams that work elsewhere.
* Reading from the Okio source directly lets the caller apply its own idle
  timeout, which matters because a reasoning model can legitimately send nothing
  for half a minute.

HTTP/1.1 is forced: some proxies mishandle h2 streaming and stall until the
response completes, which defeats the point.

Retry is restricted to transient failures **and** only while nothing has been
emitted. Once tokens have arrived, retrying duplicates a partial answer.

The reader thread hands each event to the flow with `trySendBlocking`: when the
agent falls behind, the network read waits for it. It used to be `trySend`,
which drops what does not fit the 64-event buffer, and a phone busy enough to
fall behind lost pieces of the answer (markdown that broke mid-reply) and of
tool arguments (a file written with a hole in it, an edit whose JSON no longer
parsed). Nothing reported the loss.

### Prompt caching

A provider that caches matches a prompt's prefix byte for byte, so
`SystemPrompt.parts` splits the prompt in two: everything that stays the same
for the session, and what changes per request — how full the window is, the
storage left, the environment-context block — gathered at the end. An adapter
that does not cache sends the two joined, as before. `KiroWire` sends the stable
part as the system turn and the changing part with the current message, inside
`<app-context>`, and `KiroPromptCache` puts cache points on the system turn and
on the last user turn of the history, for models whose listing reports
`promptCaching`.

Measured on Kiro, the split is what pays and the points are not: the service
caches a repeated prefix by itself, an agent turn's requests cost the same to
the third decimal with and without points, and with the window-fill line left
in the system turn every request of the turn cost the cold price, about 40%
more by its end. The numbers are in `docs/PROVIDERS.md`.

## Connection schemes

A connection used to be a form: URL, wire format, auth header, extra headers.
For a known service all of those are facts, not choices, so `ConnectionScheme`
carries them for Anthropic, OpenAI, OpenRouter and Kiro, and the form for
those is one field, the key. Custom is the only scheme that asks for a URL and
a format, because nothing about a local server can be inferred; its auth
header follows from the format and from whether a key was given. The values
and their sources are in `docs/PROVIDERS.md`.

* **The facts are re-applied on every read** (`SettingsRepository.withSchemes`),
  so a renamed header or a moved route reaches connections saved by an earlier
  version without anyone editing them. A connection saved before schemes
  existed is matched to one by its URL.
* **A key is checked where that is cheap.** Listing the models is the check
  when the listing needs the key. OpenRouter's does not, so it asks
  `GET /api/v1/key` instead (`checkPath`), and for Kiro the token exchange is
  the check. A key that does not start the way the service's keys do gets a
  warning rather than a refusal, since the prefixes are observed, not all
  documented; saving again accepts it.
* **No model list in the app.** Every scheme lists its own, Kiro's through
  `ListAvailableModels`. `ModelSource` says whether the list carried the limits
  (`FULL`) or ids only (`IDS`), and the model sheet says which.
* **One connection per service, unless the user wants two.** Picking a service
  that already has a connection without a key edits that one instead of adding
  a second.
* **No connection is created on first run.** OpenRouter used to be, waiting
  for a key, and as the only connection it was listed as active while
  nothing could be sent through it. Settings older than schema 4 drop it if
  it never got a key (`withoutUnusedFirstRun`; the key is the only sign, as
  OpenRouter lists its models without one), and keep it if it did. AnyModel,
  an earlier default, is retired the same way: settings older than schema 3
  drop it if it never listed a model, and keep it as a custom connection if
  it did.
* **"Active" is only shown on a connection that can be used.** One waiting
  for its key says so instead, even when the setting points at it.

Settings is four groups: connections, the agent (which one, its reasoning
and its model), the rules From Rules follows, and interface. The prototype's
About group is not in the app; the licence notices ship in
`assets/licenses`. The endpoint test report, adding a model by hand, editing
a context window, the split orientation and the chat's side went with the
old screen.

## Agent loop

```
build prompt -> compact if needed -> stream completion
     ^                                     |
     |                                     v
append results <- execute tools <- tool calls?
                                      | no
                                      v
                                   finish
```

* **The `FlowCollector` is threaded into the streaming function.** An earlier
  version collected events into a list and returned them, which made streaming
  decorative — the UI would freeze for the length of the answer and then fill in
  at once.
* **The assistant message is persisted empty, then updated** every 400ms.
  Streaming into memory and saving at the end loses the whole response to a
  crash or the low-memory killer. On a phone both are routine. A turn stopped
  while the reply streams saves it once more, with all that had arrived, marked
  aborted: the last periodic save could be 400 ms short, and the row stayed
  `streaming` until the next start repaired it. The chat keeps it on screen,
  marked stopped, where it used to vanish with the streaming bubble until the
  chat was reopened.
* **How a reply ended is kept with it.** Nothing read the stream's finish
  reason (`StreamEvent.Done`), so a reply cut off at its output limit, or
  stopped by a content filter, read as finished. `StopReasons` maps the
  providers' reasons (`length`, `max_tokens`, `content_filter`, `refusal` and
  their kin) to a `Block.Notice` stored with the message, and a failed
  request's reason is one too: `errorMessage` has no column, so a failed reply
  used to say nothing once the chat was reopened. The chat draws notices under
  the reply (`EndNotes`); the adapters never send them.
* **A call the output limit cut off does not run.** Its arguments are not JSON,
  and "could not parse the arguments" had the model write the same call again,
  cut at the same place, until the iteration cap. It is answered with why it
  did not run and how to split the work, and the system prompt states the
  limit up front. There is no automatic continue: the note under the reply
  says to ask for one.
* **Tool results are their own message.** Both protocols require the assistant
  message that made the calls, followed by the results.
* **Parallel only for read-only tools**, and only for consecutive ones, so
  ordering between a read and a later write is preserved. Two concurrent edits
  to the same file interleave in ways that are impossible to review. Whether
  a call is safe can depend on its arguments (`Tool.parallelSafe(args)`):
  Explorers dispatched together run together, and a worker runs alone, like
  any change.

## Tools

Thirty-eight tools (forty-one on the power build, with `pkg_install`,
`pkg_search` and `pkg_remove`), invoked through one path. `ToolRegistry.execute` owns permission
checks, approval prompts, timeouts, error shaping, and spilling oversized output
to the blob store — because a tool that forgot one of those would be a security
or budget hole.

It never throws. An exception would abort the turn, discard the other calls in
the batch, and leave the model with no way to recover. A failed call that
reports why is something the model can work with.

Denied tools are **omitted** from the declarations rather than declared and
refused. A declared-but-refused tool wastes tokens on its schema and invites
retries; an absent one makes the model plan differently from the start.

Error messages are written for a reader who will act on them. "Permission
denied" sends an agent into a `chmod` loop; the actual explanation makes it route
around the problem.

### Terminals: one registry, read by both sides

`TerminalSessions` owns every PTY in the process: the shells the user opens in
the terminal panel (`term-N`) and the commands the agent leaves running with
`sh_background` (`job-N`). They used to be two things — a background-job table
for the agent and nothing for the user — and the result was that neither could
see what the other had running. Now there is one list, shown in the panel and
given to the model.

Each session's output is consumed once, into a `TerminalBuffer`: a line buffer
with scrollback that decodes UTF-8 and ANSI incrementally (colour, `\r`,
erase, insert/delete in the line, titles). Not a character grid: shell output
is append-only with rewrites of the current line, and a line buffer re-wraps
when the panel's width changes and copies as text. A program that switches to
the alternate screen (`vim`, `htop`) is detected and reported rather than drawn
badly.

Scrollback is 2 000 lines of up to 4 000 characters, and 1 000 000 characters
in all: a style is kept per character, so 2 000 lines of a minified bundle were
tens of megabytes for one terminal. `plainTail`, what `sh_job_output` reads,
joins the lines it takes once; it inserted each at the front of the text,
copying the whole tail again per line, under the session's lock.

Lines are whole, but cursor movement follows the screen rows a long line wraps
onto at the PTY's width (`TerminalBuffer.columns`, with deferred wrap at the
last column), and the panel splits rows at exactly that width. Line editors
depend on it: mksh redraws a prompt wider than the screen by returning to the
start of its *last* row, and a buffer that took `\r` to mean column 0 printed
the prompt's tail over its head. The interactive shell is started with
`ENV=$PREFIX/etc/profile` rather than as a login shell, because a login mksh
never reads that file and then takes Android's `/system/etc/mkshrc`, whose
prompt is the full `/data/user/0/…` path.

"What is running in it" comes from the kernel, not from guessing at output:
`pty.status` asks `tcgetpgrp` on the master for the foreground process group,
and `/proc` for its name and the shell's working directory.

The model can **read** any session (`sh_jobs`, `sh_job_output`) and stop only
its own jobs. It cannot type into a terminal: there is no `sh_session_*` tool,
because a model driving a REPL keystroke by keystroke is a niche capability
with real risk. A program that needs a keyboard is the user's to run, in their
terminal, and the prompt says so.

### Environment context

The last section of the system prompt is a tagged `<environment-context>`
block (`EnvironmentContext`): the files open in the editor with the active one
marked, and every terminal with its state, foreground program, directory and
last line. It is rebuilt for every request, framed as attached automatically,
a snapshot, and data rather than instruction — it carries file names and
program output, neither of which the app wrote.

### Tool descriptions

Every description has one shape: what the tool does; *When to use it*, with the
neighbouring tool to pick instead; only the limits that change how it is called;
and *Output*, what the result actually contains. They were written against the
code rather than the intent, and that pass found tools that did not do what
their descriptions said — `symbol_search` failed on every call (`Regex.escape`
emits `\Q…\E`, which Rust's regex rejects), `build_run` was cancelled at 300s
whatever `timeoutMs` said, `conversation_read` by message id returned whichever
turn mentioned the id, and `fs_apply_patch` read a patch's final newline as a
blank context line. Those are fixed; the descriptions name only tools the agent
can have.

The second pass fixed what the first one could only describe:

* `fs_edit`'s whitespace-tolerant match replaced inside a normalised copy and
  returned it — trailing whitespace stripped from every line, CRLF turned into
  LF. Matches are now spliced into the original, and replacements and patched
  lines take the file's own line endings.
* Snapshots read files with lenient decoding, so reverting a binary file wrote
  back a corrupted one, and a file that could not be read was recorded as
  absent and deleted on revert. Only exact UTF-8 text is snapshotted now, and
  the text edit tools refuse anything else.
* `grep`'s `multiline` was a no-op (a line terminator was always set, which
  also rejects `\n`), and `project_tree` listed every directory before every
  file, away from where each file lives.
* `editor_open` waits for the editor's answer instead of reporting "opened"
  for a file the editor refused or never received.
* Supervised mode (now Confirm) asked about every call, reads included, and a
  subagent could not ask anyone, so a supervised subagent could run no tool at all.
  Calls that change nothing no longer ask; a subagent's approvals are put to
  the user in the parent conversation's dialog, labelled with its name, and
  approval dialogs are serialised — two at once used to leave the first
  waiting forever.

## Web fetch

Two tiers. Static is OkHttp plus a hand-written HTML extractor — no jsoup, since
there is no DOM to query and a parser plus a Markdown converter is ~600 KB for
the job.

The rendered tier uses the system WebView, and this is a case where Android is
*better* than a desktop: Chromium is already present as a system component, so
there is no 200 MB browser to download, and its renderer already runs in a
sandboxed process.

Opt-in, because starting a WebView is hundreds of milliseconds and running a
site's JavaScript is seconds and real battery. The static tier detects an
unhydrated shell — substantial markup, almost no text — and says so explicitly,
which is what stops the model from being handed three words from a React app and
concluding the page is empty.

Charset is detected from the `Content-Type` header and then from a `<meta>`
sniff. Assuming UTF-8 would mojibake the large part of the Russian-language web
still served as windows-1251, and plausible-looking garbage is worse than an
obvious failure.

## UI

* **Not a reversed list.** The usual chat autoscroll trick breaks here: an
  assistant turn is a group of heterogeneous items (reasoning, prose, several
  tool cards) that must read top to bottom. Scroll is driven explicitly, and
  stops the moment the user scrolls up.
* **Following is decided by the direction of the user's own scroll.** A
  nested-scroll connection on the list sees every finger scroll, the list's
  own and those of the boxes inside it (open reasoning, a command's output, a
  diff): toward the history stops following, back to the end resumes it. The
  app's own scrolling does not pass through it. Asking "is the list at the
  bottom?" after a drag re-pinned the chat while the answer still fitted on
  screen, and what the user was reading was then pushed off it. Following
  goes to the very end in one step; it used to put the top of the streaming
  message at the top of the screen, which for a long answer jumped up on
  every delta. Reasoning folds when the answer starts, not when the message
  ends, and not under a reader; the streaming item keeps the finished
  message's key, and the reasoning's scroll position is held above both.
* **Deltas do not mutate the message list.** A 4000-token answer would rebuild
  an immutable `Message` 4000 times. The in-flight message is plain strings in
  `UiState.streaming`, coalesced on a 50ms interval so a fast endpoint cannot
  drive recomposition at token rate.
* **What streams is drawn in pieces.** Twenty updates a second still parsed
  and laid out the whole reply and the whole reasoning each time, and a long
  one made the chat stutter more with every line. The reply is parsed again
  from its last blocks only (`MarkdownParser.Streaming`): a block is settled
  once the next has begun two lines before the end, and settled blocks are the
  same objects, so their views are skipped. `StreamedTextTest` checks that this
  gives what a whole parse gives, at every length of its sample replies.
  Reasoning is drawn as its paragraphs, a long one in parts of up to 1 200
  characters (`reasoningPieces`), so only the last piece is laid out again.
* **Tool cards collapsed by default.** A session makes dozens; expanded, the
  transcript is unreadable. The one-line summary stays visible.
* **An output box draws at most 40 000 characters.** Past that it shows the
  first 10 000 and the last 24 000, cut at line breaks, with a line between
  them saying how many are left out (`monoShown`); so does a command's own
  line, which can carry a file in a heredoc. A build's whole output in one text
  took seconds to lay out on the main thread. The live card used to keep only
  2 000 characters of a result while the stored card had all of it; both have
  all of it now, and what the model gets is not affected.
* **A subagent's card is a window onto its run**: folded to a line, a live
  strip while it works, a scrolling window when open. See Subagents.
* **The mode in the top bar is never cut.** The project's name gives way
  instead, and when the bar cannot hold the mode beside every icon (search,
  history, new chat, ⋮), `WorkspaceScreen` measures the mode in the user's
  font size and moves New chat into the ⋮ menu first, then History. Memory
  lives in that menu only.
* **The changes bar is never cut either.** It reads "4 files +64 −1": a
  short count, green additions and red removals, as in a diff. When the sum
  and its two buttons do not fit on one line, the buttons go to a second line
  under it (`SumThenActions`) instead of squeezing the numbers into an
  ellipsis.
* **The tree's menus follow what was held.** A file's: rename, open in another
  app, delete; a file has nothing to create in. A folder's adds new file and
  new folder, inside it, and open in the file manager. The empty space below
  the rows is the project itself: new file, new folder in its root, open in
  the file manager. The list keeps 80 dp below its last row so that space
  exists however long the tree, and the hold only counts below the last row,
  so a row's own hold never opens both. The header's buttons do the same for
  TalkBack, which has no empty space to hold.
* **A card's clock counts the tool, not the model.** A call appears as soon as
  the model starts writing it, and for a large `fs_write` most of the time is
  the model writing the content. The card says so — "writing · 0:37", with the
  size once there is more than a path ("writing 2.4 KB · 0:37"), and the path
  from the call's first fragments, which are all passed on — then "queued"
  behind the calls before it, and the tool's own seconds start when the call
  itself does (`ToolCallRunning`). Before that, the clock ran from the first
  byte, and an eight-millisecond write read as a file write that took twelve
  seconds. The writing clock matters most with Kiro, which holds a long value
  back until it is complete: a size alone stood at "8 B" for the whole wait.
  The reasoning header stops counting once the call starts.
* **Formulas are transcribed, not typeset.** Models write LaTeX whether asked
  to or not, and a phone has no room for a typesetting engine (KaTeX would be a
  WebView per formula). `LatexMath` turns `$…$`, `$$…$$`, `\(…\)` and `\[…\]`
  into Unicode: `\frac{n(n+1)}{2}` is `n(n+1)/2`, `\sum_{i=1}^{n}` is `∑ᵢ₌₁ⁿ`,
  `\rightarrow` is `→`. Scripts use Unicode only where every character has a
  form (`x^{q+1}` stays `x^(q+1)`), and an unknown command stays as written. A
  single `$` counts only around something that reads as a formula, so prices
  and `$PREFIX` stay text.
* **Diffs are unified, not side-by-side.** A phone column is ~45 monospace
  characters; two of them is 22 each, which truncates almost every real line.
  The gutter has two number columns, old and new, as on GitHub: one column
  mixed old-file numbers on removed lines with new-file numbers on the rest,
  so a run read `+42 +43 +44 −46 −47`. `fs_apply_patch` shows the file's own
  diff, not the model's patch, whose hunk headers may be off by the drift.
* **Status is never colour alone.** Tool state is a dot *and* text *and* a
  content description; diff lines differ in lightness as well as hue and always
  carry a `+`/`−` glyph. Red/green is precisely the pair that fails for the ~8%
  of men with colour vision deficiency.
* **One design, generated twice.** `design/tokens.json` drives both the HTML
  prototype (`tools/gen_design.py`) and the app's theme (`tools/gen_theme.py`
  writes `ui/theme/DesignTokens.kt`, which nobody edits by hand), so the two
  cannot drift apart. The theme is black and fixed: no dynamic colour and no
  light variant, because a 1:1 port of a design cannot follow the wallpaper.
  A press is an overlay, not a ripple (`PressOverlay` as `LocalIndication`),
  as in the prototype.
* **Bundled text fonts.** Inter for the interface and JetBrains Mono for code,
  each one variable file pinned per weight. The system sans differs between
  Pixel, Samsung and Xiaomi, and no layout survives that 1:1; both fonts cover
  Cyrillic. Ligatures are off in the mono style, so `!=` and `->` read as
  typed. `includeFontPadding` is off everywhere and line height is spread
  evenly around the glyphs, as a browser lays out the prototype. VS Code's Seti
  icon font is bundled too, for the file icons (see `TOOLCHAIN.md`).
* **Four screens, three tabs.** `ui/nav/Navigator.kt` is a small sealed
  `Destination`, no navigation library: Home (open, create or clone a project,
  and recent ones), Projects (all of them, searchable, plus copying in a
  folder from the device), a project's Workspace, and Settings. The
  workspace's tabs, Agent, Files and Terminal (`Pane`), are not destinations,
  so back does not step through tab switches. On a tablet, a foldable or in
  landscape the workspace splits: the agent keeps one side, files or a
  terminal the other.
* **Sheets, not dialogs.** Model, reasoning, context, changes, approvals,
  the agent's mode, memory, chat history, checkpoints and the file prompts
  (name, delete) are bottom sheets, as in the prototype. A tall one stops
  40 dp under the status bar, as the prototype's `max-height` does, instead
  of running under the clock. Its top edge is drawn on the sheet's content:
  Material moves the sheet into place inside its own modifier, and the edge
  drawn there stayed at the top of the screen.
* **Files is a tree or an editor, not both**, as in the prototype: a phone
  column has no room for the two side by side. Opening a file switches to the
  editor and the editor's tree button switches back; both stay composed, so
  neither loses its scroll. A file the agent changed carries M or A in the
  tree, from the same change list the chat's changes bar shows.

## Bugs found by audit, and what they taught

A pass over the finished code with `tools/audit.py` plus a manual review turned
up a set of defects worth recording, because several are general traps rather
than typos.

**Synthetic messages in the compaction plan.** The prompt prepends a rendered
digest and an FTS recall block, both carrying `seq = -1` because they are not
rows in the store. `planCompaction` treated them as archivable candidates. In
the common case the range still happened to land correctly, but when the only
candidate *was* synthetic the range became `[-1, -1]`, the store archived
nothing, and the plan was not a no-op — so the loop compacted again on the next
iteration, spending a summarising request each time up to the iteration cap.
Fixed by filtering `seq < 0`, plus an `isWorthCompacting` threshold and a
once-per-turn guard.

**PTY file descriptors leaked on normal exit.** The reader thread set the state
to `EXITED` and closed its channel, but nothing released the master fd or the
native session entry. One leak per terminal tab and per background job, against
a per-process limit around 1024 — a long session would eventually fail to open
files for reasons that look nothing like the cause. Fixed by releasing in the
reader's `finally`, with the native close made idempotent and a proper reap loop
so killed children do not become zombies either.

**`internal object` on the JNI bridge.** Kotlin mangles the JVM names of
`internal` declarations. Whether that applies to public members of an internal
container is a detail worth not betting on, because if it went the wrong way
*every* native call would fail with `UnsatisfiedLinkError` at runtime rather
than at build time. Now public, with the reason recorded at the declaration.

**Three consecutive `user` messages to Anthropic.** Digest, recall and the
actual user turn all map to `user`, and that API is strict about alternation.
`encodeMessages` now builds `(role, blocks)` pairs and merges adjacent entries,
and inserts a placeholder if a restored transcript would start on `assistant`.

**Failed tool calls rendered as successes.** The ViewModel dropped `tool`
messages from its state, so after a reload the renderer had no result to consult
and showed every historical call as OK. They are kept in state now and filtered
at render time instead, with results paired back to the calls that made them;
failures open expanded.

**Keystore crypto inside composition.** `maskedApiKey()` was called from the
settings screen's argument list, so an AES-GCM decrypt against a hardware-backed
key ran on the main thread on every recomposition, for every endpoint. Masks are
precomputed off-thread into the state now.

**`System.loadLibrary` on the main thread.** `AgentRuntime`'s `ptyManager` field
read `NativeCore.available` in an eager initialiser, and the object is
constructed in `Application.onCreate` — so the native library was mapped and
initialised before the first frame. The field is lazy now.

**Per-iteration work in the agent loop.** `prepare()` runs once per iteration,
and it was forking `git` twice and re-estimating all forty tool schemas each
time — several hundred native calls and two process spawns per round of tool
calls, for values that do not change. Both are cached, and FTS recall now runs
only on the first iteration rather than re-querying and re-prepending for an
unchanged question.

**Twelve passes over the document in the HTML extractor.** Dropped elements were
removed by scanning and rebuilding the whole string once per element name — for
an 8 MB page that is around 96 MB of allocation churn, on a device where that is
the difference between working and being killed. Now a single pass with a
nesting counter. Attribute lookups were also recompiling a `Regex` per tag.

**An approval dialog that could deadlock the agent.** The dialog lived in the
chat screen's composition while the agent waited on a `CompletableDeferred`.
Switching to Settings unmounted it with no way to answer — and switching to
Settings is exactly what the user does when the thing being approved reveals a
missing key. Hosted at the root now.

**Interrupted turns left a permanent spinner.** An assistant message is
persisted as `streaming` and rewritten as deltas arrive; a process death in
between left the row that way forever. `repairInterruptedMessages` marks them
aborted at startup.

**Unbounded snapshot growth.** Every edit stored the full before *and* after
contents for revert. Now capped per file, with the edit still applied and simply
not revertible above the limit, and a blob prune at startup.

**Dead code that looked like features.** `web_search` was unreachable —
`AgentRuntime` passed `searchEndpointUrl = null` unconditionally and no search
settings existed. Either wire it or delete it; it is wired, with settings and a
credential slot. `Tools.readOnlyToolNames` was unused while the Explorer agent
restated the list by hand, and the two had already drifted: the hand-written
copy was missing `fs_stat` and `web_fetch`, so the read-only agent could not
check whether a path existed.

## Bugs found by the first real build

The audit above was written without a compiler. When one finally ran, it found
fifteen more. The interesting ones are not the typos.

**A `/*` inside a block comment ate fifty lines of the build script.** Kotlin —
and Rust — **nest** block comments. The flavour comment in `app/build.gradle.kts`
contained the sentence "`/system/bin/*` can be executed", whose `/*` opened a
nested comment. The `*/` at the end of the comment closed only the inner one, so
the outer comment ran on until it found `*/` inside the string `"**/*.so"` in the
`packaging` block. The compiler reported 232 errors, the first of them 50 lines
past the actual mistake, and the file reads correctly to a human.

This is undetectable by review and trivial to detect mechanically, so it now has
its own checker (`tools/check_nested_comments.py`, also run from `audit.py`).

**`RTLD_NEXT` has a different value on 32-bit, and the wrong one is silent.**
The `libc` crate does not bind `RTLD_NEXT` for Android. bionic defines it as
`(void*)-1` on LP64 but `0xfffffffe` on ILP32 — and on ILP32, `-1` is
`RTLD_DEFAULT`. So hardcoding the 64-bit constant would not fail on
armeabi-v7a: `dlsym("execve")` would search from the global scope, find the
interposer itself, and every `exec` would recurse until the stack ran out. The
shim splits on `target_pointer_width`.

`environ` is the same class of problem, less dangerous: bionic exports it, the
`libc` crate only binds it for glibc-like targets, so it needs an `extern "C"`
declaration.

**`libc::Ioctl` is not the same type everywhere.** It is `c_int` on Android and
musl, `c_ulong` on glibc. The PTY constants were `c_ulong`. They are now written
as `u32` and cast through `libc::Ioctl`, which also makes the wrap to a negative
request number explicit — `0x80045430` does not fit in an `i32`, exactly as the
C macros intend.

**Backup rules that lint rejected, protecting nothing.** `<include>` makes the
backup configuration an *allowlist*; once present, anything not listed is
already excluded. The five `<exclude>` entries for `usr/`, `var/` and
`apkcli_secrets.xml` were therefore redundant, and lint failed the release build
over them (`FullBackupContent: not in an included path`). They read as the
security control, which is the dangerous part: the real control is the short
include list, and that is now stated as such.

**An error message that could only ever print `0`.** `diff.rs` reported
"`{last_counts}` partial hits" when no strategy matched, but every path that
assigned `last_counts` returned before the loop ended, so the number was always
zero. There is no partial-hit count to report; the claim is gone.

**Two event types that could never be emitted.** `AgentEvent`
declared `ToolCallAwaitingApproval` and `ToolCallProgress`, and
`AgentService.labelFor` handled the first — but `AgentLoop` emitted neither, and
could not: both would have to come from inside a tool coroutine running in an
`async` group, and a flow cannot be collected from several coroutines at once.
Only the non-exhaustive `when` in `ChatViewModel` revealed it.

`ToolCallProgress` is gone, along with the unused `ToolContext.onProgress` that
no tool ever called. Live output came back later by a route that is legal:
tools post the latest screenful to `ToolContext.onOutput`, a channel, and the
loop drains it with `select` *in the flow's own coroutine* while a group runs,
emitting `ToolOutput` events there. The awaiting-approval *behaviour* was real
and missing, so it moved to the path
that works: `ApprovalGate.request` now carries the `callId`, and the ViewModel
marks that row `PENDING` and relabels the notification. Previously the shade
claimed "Running sh_exec" while the agent sat blocked on a dialog.

**Configuration cache: capturing script state, not lambdas.** `onlyIf { buildRust }`
on the cargo task failed with "cannot serialize Gradle script object references".
The cause is specifically that `buildRust` is a script-level `val`, so the spec
captures the build script object; `envReport`'s `doLast`, which touches nothing
from the script, caches fine. The ABI switch is applied by not wiring the task
dependency at all, which keeps it out of the graph instead of putting it there
to be skipped.

**Two imports deleted by my own unused-import pass.** `jsonObject` in
`Compactor` and `Job` in `LoadGovernor` were both in use. A heuristic that
deletes code needs to fail closed, and that one did not.

Smaller items: `private` base classes under public subclasses (`GitTool`,
`BuildLikeTool`); a smart cast the compiler refuses across module boundaries on
a public property; `Icons.Filled.List` instead of the auto-mirrored variant, which
does not flip in RTL; OkHttp 5's non-null `Response.body` leaving dead elvis
branches in both adapters and `WebFetcher`; the deprecated
`jniLibs.srcDirs(...)` in favour of the `directories` set; and
`android:extractNativeLibs` written by hand in the manifest when AGP owns it and
derives it from `useLegacyPackaging` — verified still `true` in both merged
manifests after removing it, since the entire exec design depends on that flag.

## Bugs found while making endpoints custom

**There was no way to type a URL.** Endpoints came from a preset list, and the
card only displayed the URL. The "Custom endpoint" preset created an endpoint
whose `baseUrl` was `""` with nothing anywhere able to change it;
`SettingsViewModel.updateEndpoint` had no caller and `Destination.EndpointDetail`
was unreachable. The presets went, and an editor screen took every field,
validated against what OkHttp will actually accept in a header (a pasted key
with a trailing newline used to surface as an exception inside a streaming
call), and showed the exact request URL as you typed. That screen has since
given way to connection schemes (below): a known service is its key alone,
and only the custom form asks for a URL, with the same header check.

**"Models" could never work on a device.** `listModels` called OkHttp's blocking
`execute()` on the caller's thread, and the caller was `viewModelScope` — the
main thread — so it threw `NetworkOnMainThreadException`, which the adapter
swallowed into "this endpoint does not expose /models". The endpoint test made
the same mistake and reported the listing step as passed. Listing now runs on
`Dispatchers.IO`, only a 404/405 means "no route", and every other failure is
shown as the error it is.

**Tools ran on the main thread.** `LoadGovernor.admit` took a permit and then
ran the block in the caller's context — which, for a turn collected in
`viewModelScope`, was the main thread. So grep, file reads and the native search
engine blocked the UI, while the per-class pools with their affinity and nice
levels sat idle. `admit` now switches to the class's pool, and the whole turn
producer runs under `flowOn(Dispatchers.Default)`.

**Local servers were unreachable.** Without a network security config, Android
blocks every `http://` request for targetSdk 28 and above — both flavours — so
LM Studio or llama.cpp on the LAN failed with "CLEARTEXT communication not
permitted". Cleartext is allowed now, and the risk is handled in code:
`Http.transportRefusal` will not attach an API key to plain HTTP unless the host
is loopback, a private range, CGNAT/Tailscale or a LAN name (`NetworkHosts`).

**Anthropic-protocol endpoints depended on a preset.** The required
`anthropic-version` header came from the Anthropic preset's extra headers.
Without presets every hand-entered Anthropic-compatible endpoint would have got
a 400; `Http.applyAuth` now supplies the header, and an extra header still wins.

## Commands that hung forever

`sh_exec`, builds and the shell tools "ran" indefinitely, and `web_search`
and other tools queued behind them. Three faults compounded:

* **Waiting for EOF, not for the command.** The native runner joined its pipe
  readers until end of file. `cmd &`, or `pg_ctl start` without `-l`, leaves a
  child holding the pipes after the shell exits, so the call never returned.
* **The timeout killed the shell only.** Its children kept running and kept
  the pipes open.
* **A blocking JNI call on a pool thread.** `withTimeout` cannot interrupt
  one, and the call held a governor permit while it waited.

`native/apkcli-core/src/proc.rs` replaces it with a stepped API:
`proc.spawn` starts the command in its own session and process group
(`setsid`), `proc.step` waits up to 100 ms and returns the new output and
the state, `proc.close` ends it. `ShellRunner` drives the steps from Kotlin,
each one a short call on `Dispatchers.IO`, so cancellation works between
steps; `finally` closes the process, so a cancelled call kills its group.
On timeout the group gets SIGTERM, then SIGKILL two seconds later. Once the
shell has exited, output is drained for 500 ms; whatever in the group still
holds the pipes is named in a note and stopped. A process that let go of the
pipes (`nohup cmd > log 2>&1 &`) or moved to a session of its own
(`pg_ctl start -l log`) is left running: that is what it asked for. Partial UTF-8 sequences carry over between reads, and stdin is
written from its own thread with SIGPIPE blocked.

Every tool also has a deadline in `ToolRegistry` that always returns, with
"did not finish" or "could not start" as the reason. `web_search` and
`web_fetch` cancel their OkHttp call when the coroutine is cancelled
(`Http.executeCancellable`) and have a call timeout of their own.

### Commands print on a terminal

Live output was not enough: on a pipe `npm install` prints nothing until it
is done, and pip, cargo and most build tools hold back their progress and
colour or buffer whole blocks, so a running card stayed empty. `sh_exec`,
`build_run` and `test_run` therefore run with `tty: true`: `proc.spawn` opens
a pseudo-terminal (120×40, `TERM=xterm-256color`) and makes its slave the
command's stdout and stderr. The slave is opened `O_NOCTTY` and never made a
controlling terminal, and stdin stays `/dev/null` or the given text, so
nothing can wait for keys — opening `/dev/tty` fails with ENXIO. What held for
pipes holds here: the master reads EIO, end of file, once the last holder of
the slave has closed it, and a background job still holding it is found and
stopped the same way. Pagers are off (`PAGER=cat` and the like) and git
cannot prompt.

The output is read through `TerminalBuffer`, the line buffer the terminal
panel uses, wrapped as `TerminalCapture`: `\r`, cursor movement and erase are
applied, so a progress bar redrawn a thousand times is the one line it ended
on. That goes to the live view under the card (the last lines, with colour,
wrapped to the phone's width, the newest at the bottom) and to the model
(plain text, stderr where it was printed, the start and the end kept past
2 MB, a screen clear not losing what came before it). The costs: programs that
lay out for the width, `ls` in columns, do so, and stdout and stderr can no
longer be told apart. The tool description says both.

`pkg_install` is the app's own code rather than a command, so it prints what
apt would (`PackageProgress`): finished steps stay listed, the step in hand
is rewritten as bytes arrive, and `LiveLog` posts that view at most four
times a second.

The live view under a card scrolls (it holds the last 200 lines) and stays
at the bottom unless the user drags it up.

### The agent's terminal

Each chat also has a terminal in the Terminal tab that shows what the agent
runs the way a desktop agent's commands appear in a terminal window: a green
prompt with the directory and the command, everything it prints (raw, colours
and progress bars included), how it ended (`[exit 0 · 1.2s]`,
`[interrupted · …]`), and a prompt left waiting for the next one. It is a
`TerminalSessions.Terminal` of kind `TRANSCRIPT`, without a PTY of its own,
written through `ExecEcho`: `ShellRunner` echoes each `proc.step`'s output,
`pkg_install`/`pkg_remove` echo their `LiveLog` lines, and `sh_background`
notes which job a command went on in. Commands still run as before, each in
its own process group with its own timeout: they are not typed into a shared
interactive shell, which would lose the group kill, the deadline and the clean
working directory per call.

Ctrl-C there sends SIGINT to the running command's process group
(`proc.signal`, which allows INT, TERM, HUP and QUIT only); the command ends
as it would at a keyboard and the agent reads how. Transcripts are not
sessions to the agent: `sh_jobs`, `sh_job_output`, `sh_job_kill` and the
prompt's terminal list skip them. The four newest are kept.

The tab shows every terminal of the project in one row, as the prototype
does: the user's shells and the jobs the agent left running are "Shell #N",
each named with the lowest number free in the project when it first appeared
and keeping it (`Terminal.number`, so the names outlive the screen). A chat's
transcript is named "Agent" ("Agent #2" for a second chat's) and numbered
apart from the shells: called "Shell #1" beside the user's "Shell #2", it read
as a shell the agent had declined to use, when it is where the agent's
commands are shown. The first shell opens by itself, any terminal can be
closed, the last one too, and the tab does not reopen one the user has just
closed: it says no terminal is open, with the button that opens one. The
interactive shell's prompt is green too, set by the bootstrap profile: bash
gets `\[\e[32m\]`, mksh the `\001\r` delimiter form, because the two mark
non-printing characters differently.

A transcript takes typed lines as well (`TranscriptInput`). Each runs the way
the agent's commands do, one at a time, on a terminal but with stdin not
connected to it, so a program that waits for keys reads end of input instead
of hanging; a shell is the place for those. There is no shell behind a
transcript to keep a working directory, so each line reports where it ended
with an `OSC 7` (the escape shells use to tell a terminal their directory),
`TerminalBuffer` picks it out, and the next line starts there: `cd src` then
`ls` lists `src`. Lines run in the process's scope, so a build typed there
goes on when the screen is gone.

## Agents and permissions

The user picks one of three modes. They are built-in agents that differ only
in when they ask (`BuiltInAgents`, `AgentDefinition.Autonomy`), and their
names are the same in every language, as the user named them:

* **Confirm** (`builtin-confirm`, `SUPERVISED`) asks before every change: a
  file written, moved or deleted, a command, a package, a subagent.
* **From Rules** (`builtin-rules`, `RULES`) asks about a change unless its
  kind is ticked in Settings › Rules. The kinds are `ApprovalRule` groups:
  editing files; deleting and moving them, with `revert_edit`; commands,
  builds and tests; background jobs; packages; subagents. A fresh install
  ticks editing (every edit can be taken back from the changes list) and
  subagents. The ticks are `AppSettings.approvalRules`, copied into the
  agent's `autoApproved` on every settings read, so the checklist and the
  agent cannot disagree. A tool in no group always asks.
* **Bypass Mode** (`builtin-bypass`, `AUTOPILOT`) asks about nothing,
  deletions included. Its description says what that costs: edits are
  snapshotted and revertible, a deleted folder or binary file is not.

Calls that change nothing (reads, searches, listings) never ask, and neither
does bookkeeping (`task_list`, `memory_write`, `memory_forget`). Past that the
mode alone decides (`ToolRegistry.needsApproval`). Destructive tools used to
ask under every mode, which made the mode that "asks nothing" ask anyway.

A tool call is checked against the mode selected *now*, not the one the turn
started with (`TurnConfig.liveAgent`): switching to Bypass Mode while a
question is on screen answers it, and a rule ticked mid-turn reaches the next
call. The approval sheet offers each mode's way out: From Rules, "Allow and
stop asking" for the rule the call falls under, which ticks it; Confirm, a
switch to Bypass Mode. The prompt and the tool list stay those of the turn's
agent; the prompt's role line names the mode and, under From Rules, which
kinds go ahead and which wait (`SystemPrompt.roleLine`).

Earlier builds offered Engineer, Runner and a read-only Explorer.
`BuiltInAgents.RETIRED` maps old settings on their first read: Engineer to
Confirm, Runner to Bypass Mode, and a chat left on the Explorer to Confirm.
The model chosen for the old agent carries over, and the user's own agents
are kept. A hidden or missing active agent falls back to Confirm.

### Subagents

`subagent_dispatch` hands a task to one of two agents:

* The **Explorer** (`builtin-explorer`, `READ_ONLY`) is hidden from the mode
  list and exists for this. It has only the read-only tools and never asks.
* The **worker** (`AgentRuntime.WORKER_ID`) is the parent's own mode, read on
  every call, so a subagent's change asks exactly when the parent's would,
  and ticking a rule or switching to Bypass Mode reaches it at once. Its
  questions go to the parent conversation's approval sheet under the
  subagent's name. A mode's id or a retired agent's id dispatches a worker
  too; an agent of the user's own runs as itself.

The model picks one per call. Explorers dispatched in the same reply run at the
same time; a worker runs alone, like any change. Two workers side by side
could edit one file at once, or run two builds in one directory, and nothing
kept them apart. An agent of the user's own runs alone as well, read-only or
not: the batch is split before any agent is looked up. Subagents get no
dispatch function, so they cannot nest, and no
`UiBridge`, so they cannot open files or touch the task list. Each runs in a
session of its own, archived from the moment it is created: a session that is
not archived is a chat, and a running subagent's was listed in the chat
history, where it could be opened or deleted from under it, and after a crash
was the chat the app reopened. Archived, it is out of the history and still on
disk. Its file edits are recorded under the parent chat, so they appear in that
chat's changes bar and its checkpoints take them back.

What the parent model gets is the subagent's text, everything it wrote in
order, as the tool's result, with a note when its round limit or its output
limit cut it short; a long one is spilled past the tool budget like any
result. The dispatch has a deadline of its own, 45 minutes: the default five
cut long investigations off with everything found lost. A run that failed is a
failed call, and stopping the parent stops it.

**The card** is a window onto the run, as a desktop agent shows a subagent
(`SubagentCard`, designed in the prototype first):

* Its head is a tool card's: "Subagent", the kind (read-only, changes files,
  or the name of the user's agent), the task's first line, status and clock.
* While the model writes the task, the folded card shows it arriving, read
  from the call's arguments before they are complete. While the subagent runs,
  the folded card shows its last three steps and what it does now: waiting for
  the model, or the words it is writing.
* Open, it is a window of up to 420 dp that scrolls inside and follows the
  newest step: the task (four lines until "Show all"), the files to start from,
  the steps (each opens on its output as the subagent's model received it, or
  on its reasoning), the answer, and a note for a round limit, a failure, a
  stop, a refusal, or a last reply cut at the output limit. Done, it folds back
  to its head.

**The record** behind it is a `SubagentRun`, built from the subagent's own
events by `SubagentTracker`. A round's text becomes a step when a call follows
it, said on the way, and is the answer when none does; reasoning is a step of
its own. While it runs, a ticker posts the run every 250 ms if it changed
(`SubagentRequest.onProgress`, `ToolContext.onSubagent`,
`AgentEvent.SubagentProgress`), through the channel a command's live output
takes; each replaces the last, so one dropped costs nothing, where a snapshot
per token would redraw the chat at the provider's rate. The last one is stored
with the dispatch call's result (`Block.ToolResult.subagent`), so a reopened
chat shows the same card, and like the diff there it is never sent to the
model. The task is not in it: that is the call's arguments, whole. The record
is bounded and says so: 150 steps, the older ones counted; 2 000 characters of
a step's text and 20 000 of the answer, each with its full length beside it
("Showing 2,000 of 9,400 characters"); and the first 400 of a call's
arguments, where its path or command is. The archived session keeps everything
whole.

**Approvals.** A worker's questions go to the parent chat's sheet through
`ApprovalGate.Labelled`, which marks the step waiting on the card, then running
or declined, and passes the subagent's name: the sheet says "Asked by a
subagent · Worker". Dispatching asks as any change does under the mode; its
sheet shows the task as prose, and whether the subagent only reads or works
under the chat's mode. An approval's detail is the call's arguments whole. It
was a 1 200-character preview with the whitespace squeezed, which stopped being
JSON for a long command; the sheet then fell back to the 120-character summary,
and the user approved a command they could not read to the end.

## Checkpoints

Every message of the user's has a row above it, "Checkpoint · Restore", as in
Kiro. Restoring takes the chat, the agent's context and the files it changed
back to the moment that message was sent, and puts the message itself, text
and images, back in the composer to be edited and sent again.

* **Files first, history second** (`ChatViewModel.restoreCheckpoint`). A
  running turn is stopped and waited for, since it would go on writing into
  the history being taken back. `ChangeLedger.sinceCheckpoint` lists every
  file the agent changed since, each to go back to how it was before the
  first of those changes. Reverted and accepted snapshots count too: a file
  the user reverted and the agent then edited again was, at the checkpoint,
  what its first snapshot since remembers. Files already in that state are
  left out, so the number the confirmation shows is the number that changes.
* **History in one transaction** (`message.rewind` in the Rust store). The
  message and everything after it go, with their tool calls and spilled
  output, and so does every digest written since; the messages those had
  archived are live again, so the next request carries what it would have
  carried then. Snapshots taken since go with the history, except those of
  files that could not be put back (`keepSnapshots`), which stay in the change
  list rather than vanish with the messages that explained them. Seqs continue
  from the message's own, and a chat taken back to its first message is named
  again by the next one. A failure leaves the session as it was.
* **What it cannot undo** is a change made through the shell, which was never
  snapshotted. The confirmation says so, next to the number of messages that
  go (each of the user's, and each reply of the agent's however many records
  it took) and the number of files restored.
* A subagent's edits are recorded under the chat that started it, so a
  checkpoint takes those back too.

## Chats

A project has as many chats as the user starts, and a new chat does not end
the previous one. The history sheet (the clock in the top bar) lists the open
project's chats, newest first, under Today, Yesterday and Earlier, with a
search field once there are more than five. It reads `store.listSessions`,
which leaves out archived sessions (subagents' are), filtered by the project's
path. There is no list of every project's chats: a chat makes sense only with
its project open, and the home screen already lists the projects by when they
were last used.

* **No pile of empty chats.** "+" from a chat with nothing sent in it leaves
  that chat open instead of making another; leaving an empty chat for another
  deletes it; empty chats from earlier builds, one per tap of "+", are deleted
  when the sheet loads.
* **A running turn keeps its chat open.** The other rows wait, and the open
  one pulses where its menu would be; other chats can still be renamed or
  deleted.
* **Deleting** removes the chat's messages, spilled output, digests and
  checkpoints (`session.delete`); the project's files stay as they are.
  Deleting the open chat opens the most recent other one, or a new one.
* **Renaming** replaces the title `AgentLoop` takes from the first request.
  The store stamps `updated_at` on every session update, so a renamed chat
  moves to the top of the list.

### Find in the chat

The magnifier turns the top bar into a search field and the composer into the
matches' bar: "3 of 12", ↑ to older matches and ↓ to newer, both wrapping,
starting at the newest, where the reader is. The keyboard's search key steps
back; back ends the search, and so does leaving the chat for another tab.
What is searched is what the transcript shows: the user's messages, the
answers as rendered (without their Markdown), the reasoning, each tool card's
target and its output or diff lines, and a subagent card's task, files, steps
and answer. The turn still streaming is not.

* **One key per piece of text, on both sides.** A paragraph, a piece of
  reasoning, a table cell, a diff line, a subagent's step is a *segment* —
  `u`, `think:2`, `md:3.1`, `tool:<id>:b`, `tool:<id>:d:<row>`,
  `tool:<id>:sub:s4` — and the code that lists the text to search
  (`ChatScreen.findItemOf`, `markdownFindSegments`, `diffFindSegments`,
  `reasoningPieces`, `SubagentFind`) and the text that draws it
  (`rememberFindable`) use the same keys, so the match counted is the match
  marked. A tool's output is searched as its box draws it (`monoShown`), so no
  match is counted in the part a long output leaves out. Matching is by
  region, case-insensitive, on the text itself: a lowercased copy can differ
  in length.
* **The current match is held by where it is** (message, segment, offset),
  not by its number, so a turn landing meanwhile does not move the reader.
  Matches are found off the main thread whenever the words or the transcript
  change, and the bar keeps the last count until the next one is ready.
* **Parts open for a match.** A collapsed tool card, subagent card or
  reasoning that holds one is open while the search is on, and so is a long
  subagent task.
* **Showing a match takes two steps.** The list scrolls the match's message
  on screen, then the text that holds it brings the match into view with a
  margin, through whatever scrolls it (the transcript, a card's output box, a
  code block sideways), and once more after a part that opened for it has
  finished growing.

## Memory

Two scopes of short facts (`memory_write`, at most 500 characters each): one
per project, keyed by its directory, and one for every project. Both go into
the system prompt on every request, under the project's instruction file
(`AGENTS.md` and the other names in `ProjectInstructions.FILES`) — the global
scope used to be kept but never shown. Memory in the workspace's ⋮ menu opens
them in a sheet for viewing and editing, and creates or opens `AGENTS.md` for
longer notes.

Of the instruction file a request carries 32 KB. A longer one goes in up to
its last whole line within that, followed by a line saying how big it is and
the `fs_read` offset where the rest begins, and the memory sheet says so in
amber. It used to be skipped without a word, and the next name read instead,
or none: the agent worked without the project's notes, and nobody knew.

## Opening a project

`ProjectImport` copies an existing project into `~/projects/<name>`, where
the sandbox, the tools and execution all work; shared storage would need a
storage permission and cannot execute anything. From GitHub it downloads the
codeload tarball (`tar.gz/<ref or HEAD>`; public repositories, no git and no
history) and unpacks it with the package installer's `TarReader`, refusing
paths and symlinks that leave the target. From the device it copies a folder
picked with the system picker (`OpenDocumentTree`). Both write to a hidden
staging directory and rename it into place, so a failed or cancelled import
leaves nothing behind; both are capped (200 000 files, 2 GB).

The other way out is `ProjectDocuments`, a `DocumentsProvider` for
`~/projects`: the system file manager shows the projects as one of its places,
so files can be copied in from Downloads, opened elsewhere or shared. The
Files tab's "Open in file manager" opens it at the current project (a
document URI, located through `findDocumentPath`; without that the file
manager lands at its own default place), and a folder's menu at that folder.
A file's menu offers it to other apps through the `FileProvider`, in the
system chooser with this app left out: source files go as `text/plain`,
since almost no viewer declares `application/javascript` or `text/x-kotlin`,
and with only this app taking those types the file opened right back here.
Only the projects
directory has a root; `$PREFIX` and the state directory, with the
conversation database in it, are not reachable, and every document id is
checked against the canonical path, so neither `..` nor a symlink in a
project leads out. The provider is guarded by `MANAGE_DOCUMENTS`, which only
the system's document UI holds.

## Images

The composer takes up to four photos or screenshots from the system photo
picker (no storage permission), with or without text. They are turned
upright from EXIF, scaled to 1568 px on the long edge, re-encoded as JPEG 85
(transparency flattened onto white) and sent as `Block.Image`; each is
counted as width × height / 750 tokens, at most 1600. A model whose endpoint
says it takes no images gets a warning in the composer, not a refusal.

## Python packages with native code

PyPI has almost no Android wheels, so pip compiles C and Rust extensions on
the phone; Rust ones (pydantic-core, tokenizers) take far longer than a tool
call may. The first start writes `~/.config/pip/pip.conf` once (a user's edit
or deletion is kept): the Termux User Repository's wheel index as an extra
index, and `only-binary` for the Rust packages it has wheels for, so pip picks
the newest release that has one instead of compiling — `pip install pydantic`
takes pydantic 2.12.5 with pydantic-core 2.41.5 from it. Its extension modules
need only libpython and bionic, so they load from this prefix although they
were built for Termux's. That index is a community project, trusted the way
Termux's own repository is. After a failed pip build `ShellHints` names the
Termux package that ships it prebuilt (`python-numpy`, `python-cryptography`…).

## Bounds, and saying when one is reached

The rule: every buffer and every cut has a bound, and what lies past it is
said, to the model in the result and to the user on screen. A pass looking for
the opposite (a cut that said nothing, a buffer that grew with the chat, work
redone in full twenty times a second) found the items under the table; the
larger fixes are described with the code they are in. Two bounds still say
nothing when they are reached, both in the terminal panel: a flood that
overruns a PTY's channel, and a line longer than 4 000 characters.

| Where | Bound | Past it |
| --- | --- | --- |
| The live window | the prompt budget, in tokens; no count of messages | compacted into a digest |
| A frame of Kiro's event stream | 16 MB | the stream ends with an error |
| Events from a stream to the loop | 64 waiting | the network read waits (`trySendBlocking`) |
| Live output and subagent progress to the chat | 64 waiting, the oldest dropped | nothing is lost: each replaces the last |
| A PTY's output to its terminal | 1 024 chunks, the oldest dropped | a glitch in a flood instead of a stall |
| A terminal's scrollback | 2 000 lines of 4 000 characters, 1 000 000 in all | the oldest lines go; a longer line's rest is not kept |
| A command's live view | 200 lines of 500 characters, four times a second | the result is separate |
| A command's output to the model | 2 MB a stream | its start and its end, with a note |
| A tool result in the context | 4 000 tokens (`toolResultTokenCap`), a half then a quarter as the window fills | its start and its end; the whole in a blob for `conversation_read` |
| A page | 2 MB downloaded (8 MB at most); 40 000 characters of text (`maxChars`, up to 200 000) | the result says the download stopped, or where the rest is |
| `fs_read` | files of 8 MB, 2 000 lines a call (up to 5 000) | refused, or "(more follows)" |
| A stored diff | 400 lines, 48 KB | the card says so; the editor has it whole |
| An output box on screen | 40 000 characters | its start and its end, with how much is not shown |
| A subagent's record | 150 steps, 2 000 characters a step, 20 000 of the answer | counted, and said on its card |
| Kiro's text for a call or result it cannot pair | 4 000 characters | how many more there were |
| A Kiro request | 600 000 bytes | a refusal is read as too long |
| Remembered facts in the prompt | 6 000 characters a scope | how many more, and `memory_read` |
| The project's instruction file | 32 KB, to a whole line | the prompt says where the rest begins, the memory sheet that it is cut |

What the pass found, besides what is described above (Counted whole, sent
whole; the agent loop; the terminals; the UI; subagents):

* `conversation_read` returned turns as the search index holds them, each tool
  result cut to 400 characters. It returns them whole now, and a range too long
  for one result is spilled like any other.
* `web_fetch` did not say when the download stopped at its cap, nor, when the
  blob write failed, that the rest of a long page had not been stored.
* `KiroWire` sends a call or result it cannot keep structured as text, and cut
  that at 4 000 characters mid-word as if it were whole; it says how many were
  left out now.
* An instruction file over 32 KB was skipped without a word (see Memory), and
  two workers dispatched in one reply ran side by side (see Subagents).
* `tools/audit.py` reported ten imports as unresolved that compile: it knew one
  level of nested declarations and no unnamed companion. It reads both now.

## Known gaps

* **A stopped subagent keeps only its task.** Its card is rebuilt from the
  dispatch call's result, and a call stopped before it ended has none, so a
  reopened chat shows the task and "Stopped". The steps are in its archived
  session, which nothing displays.
* **Not run in the app yet:** the subagent card, and the fixes of the bounds
  pass (stopped calls answered, notices, counting whole, the whole window, the
  output box, streaming in pieces, the instruction file's cut and its note,
  workers one at a time). They are unit-tested (`SubagentTrackerTest`,
  `TranscriptTest`, `StreamedTextTest`, `ProjectInstructionsTest`,
  `ParallelCallsTest`), and the build starts on the emulator and reopens an
  existing chat, but no subagent has been dispatched from the app, and no reply
  stopped or cut at its limit there.
* A reply cut at its output limit is not continued by itself; the note under it
  says to ask.
* The terminal renders lines, not a grid, so full-screen programs are reported
  rather than drawn. Input is a line at a time, plus keys for Ctrl-C, Ctrl-D,
  Tab, history and Esc.
* **Kiro has run against a live account**, on the emulator: chats, tool calls
  and the prompt-cache measurements in `docs/PROVIDERS.md`. Anthropic's
  adaptive thinking is still unit-tested against the documented listing shape
  only, not run against the API.
* **The approval modes have not met a model.** Confirm, From Rules (with
  "Allow and stop asking") and Bypass Mode are unit-tested
  (`ApprovalModesTest`), but no turn in the app has stopped for approval
  under them yet: the live turns ran under the old Runner. The approval flow
  before the modes ran against a scripted local server that answers like an
  OpenAI-format endpoint.
* **Checked at one width.** The history sheet, find in the chat and the top
  bar have run on a 411 dp emulator screen; 360 dp, where the top bar moves
  its icons into the menu, has not been looked at in the app.
* Two departures from the prototype: the editor keeps undo, redo and an
  explicit save on its tab row and a wrap toggle in the status line, which the
  prototype's editor does not have; and a tool card's duration includes the
  time its approval waited.
* The `:shell` process split, described above. The rendered `web_fetch` tier
  also runs on the main thread for the same reason, which is a documented cost
  of that opt-in path.
* Live output reaches the transcript from the shell-backed tools (`sh_exec`,
  `build_run`, `test_run`) and the package tools; other long tools report only
  at the end.
* Opening a project copies it; there is no link back to the folder on shared
  storage or to the GitHub repository (no git, no pull).
* On-device Android app compilation (aapt2, d8, ecj) is not attempted. Shell
  build commands work; there is no remote build node, and the prompt no longer
  mentions one (it used to name a `remote_run` tool that never existed).
* No version control. The `git_*` tools (`GitTools.kt`) are kept but not
  registered, and the prompt carries no git state. `git` as a Termux package can
  still be installed with `pkg_install`, like any other package.
* `Message.model` is not persisted — the store has no column for it — so it is
  lost on reload. `errorMessage` has none either; a failure's reason is kept as
  a `Block.Notice` in the content instead, and the status is stored.
* **Emulator only.** The shell, packages, the PTY and the terminal panel have
  run on an API 34 x86_64 emulator, not on a physical device. The environment
  context is unit-tested on the JVM; a turn that sends it has not been run in
  the app. See the README.
