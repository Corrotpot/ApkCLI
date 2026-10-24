/*
 * ApkCLI design prototype — screens and behaviour.
 *
 * Reads DESIGN (tokens, Codicons paths, Seti tables, the launcher logo),
 * injected by tools/gen_design.py. No framework: state in S, render() paints
 * the viewport, clicks are dispatched by data-a attributes. Treat this as the
 * spec for the Compose screens, not as code to port.
 */

const T = DESIGN.tokens;
const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ text */

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** Russian plural: plural(2, 'файл', 'файла', 'файлов'). */
function plural(n, one, few, many) {
  const m100 = n % 100, m10 = n % 10;
  if (m100 >= 11 && m100 <= 14) return many;
  if (m10 === 1) return one;
  if (m10 >= 2 && m10 <= 4) return few;
  return many;
}

/** Relative time, as the project list shows it. */
function ago(min) {
  if (min < 1) return 'только что';
  if (min < 60) return `${min} мин назад`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} ${plural(h, 'час', 'часа', 'часов')} назад`;
  const d = Math.floor(h / 24);
  if (d === 1) return 'вчера';
  if (d < 7) return `${d} ${plural(d, 'день', 'дня', 'дней')} назад`;
  const w = Math.floor(d / 7);
  return `${w} нед. назад`;
}

/** duration_s: "0,4 с" — a comma, as the Russian locale formats it. */
function secs(ms) {
  if (ms < 1000) return `${Math.round(ms)} мс`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1).replace('.', ',')} с`;
  return `${Math.floor(ms / 60000)} мин ${Math.floor((ms % 60000) / 1000)} с`;
}

/** ToolCallCard's elapsedClock: "0:27", "12:05". */
function clock(ms) {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function mb(bytes) {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1).replace('.', ',')} КБ`;
  return `${(bytes / 1048576).toFixed(1).replace('.', ',')} МБ`;
}

/** Token counts are decimal: 1048576 and 1000000 both read as 1M, as everyone writes them. */
function tokens(n) {
  if (n >= 1e6) return `${String(Math.round(n / 1e5) / 10).replace('.', ',')}M`;
  if (n >= 10000) return `${Math.round(n / 1000)}k`;
  if (n >= 1000) return `${(n / 1000).toFixed(1).replace('.', ',')}k`;
  return String(n);
}

/* ----------------------------------------------------------------- icons */

/** A codicon, drawn from the same path data the app's Codicons.kt holds. */
function ic(name, size, cls) {
  const icon = DESIGN.icons[name];
  if (!icon) return '';
  const n = size || T.size.icon;
  const paths = icon.p
    .map(([d, evenOdd]) => `<path d="${d}"${evenOdd ? ' fill-rule="evenodd" clip-rule="evenodd"' : ''}/>`)
    .join('');
  return `<svg class="i${cls ? ' ' + cls : ''}" width="${n}" height="${n}" viewBox="0 0 ${icon.vp} ${icon.vp}" aria-hidden="true">${paths}</svg>`;
}

/** SetiIcons.forFile: the exact name, then each extension, then the default. */
function setiGlyph(name) {
  const s = DESIGN.seti;
  const lower = String(name || '').toLowerCase();
  let index = s.names[lower];
  if (index === undefined) {
    let dot = lower.indexOf('.');
    while (dot >= 0 && dot < lower.length - 1) {
      const ext = lower.slice(dot + 1);
      if (s.exts[ext] !== undefined) { index = s.exts[ext]; break; }
      dot = lower.indexOf('.', dot + 1);
    }
  }
  if (index === undefined) index = s.default;
  return { ch: String.fromCharCode(s.codes[index]), colour: s.colours[index] };
}

/** A file-type icon: the Seti glyph, centred in a box and free to overhang it. */
function seti(name, size) {
  const n = size || T.size.iconSmall;
  const g = setiGlyph(name);
  const px = (n * T.font.setiScale).toFixed(2);
  return `<span class="seti" style="width:${n}px;height:${n}px"><b style="font-size:${px}px;color:${g.colour}">${esc(g.ch)}</b></span>`;
}

function avatarHue(name) {
  const lower = String(name || '').toLowerCase();
  let sum = 0;
  for (let i = 0; i < lower.length; i++) sum += lower.charCodeAt(i);
  return T.avatar.hues[sum % T.avatar.hues.length];
}

/** A project tile: its first letter, in the hue its name hashes to. */
function avatar(name) {
  const hue = avatarHue(name);
  const match = String(name || '?').match(/[\p{L}\p{N}]/u);
  return `<span class="avatar t-avatar" style="background:${hue}${T.avatar.alpha};color:${hue}">`
    + `${esc((match ? match[0] : '?').toUpperCase())}</span>`;
}

/* ----------------------------------------------------------- highlighter */

/*
 * The editor's lexer, cut to what the prototype shows: ordered rules, tried
 * at the cursor, one character of plain text when none match. Colours are
 * the --syn-* variables, which are VS Code Dark Modern's.
 */
const KW = {
  python: /^(?:def|class|lambda|import|from|as|global|nonlocal|async|await)\b/,
  control: /^(?:return|if|elif|else|for|while|try|except|finally|with|raise|yield|pass|break|continue|assert|del|in|not|and|or|is)\b/,
};

const SYNTAX = {
  python: [
    [/#[^\n]*/y, 'comment'],
    [/(?:[rbfuRBFU]{0,2})("""[\s\S]*?"""|'''[\s\S]*?''')/y, 'string'],
    [/(?:[rbfuRBFU]{0,2})("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')/y, 'string'],
    [/\b(?:def|class|lambda|import|from|as|global|nonlocal|async|await)\b/y, 'keyword'],
    [/\b(?:return|if|elif|else|for|while|try|except|finally|with|raise|yield|pass|break|continue|assert|del|in|not|and|or|is)\b/y, 'control'],
    [/\b(?:True|False|None)\b/y, 'constant'],
    [/\b(?:self|cls)\b/y, 'variable'],
    [/@[A-Za-z_][\w.]*/y, 'function'],
    [/\b[A-Za-z_]\w*(?=\s*\()/y, 'function'],
    [/\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][-+]?\d+)?)\b/y, 'number'],
    [/\b[A-Z][A-Za-z0-9_]*\b/y, 'type'],
  ],
  sh: [
    [/#[^\n]*/y, 'comment'],
    [/"(?:[^"\\\n]|\\.)*"|'[^'\n]*'/y, 'string'],
    [/\$\{?[A-Za-z_]\w*\}?/y, 'variable'],
    [/\b(?:if|then|fi|for|in|do|done|case|esac|while|else|elif|export|return|local|function)\b/y, 'control'],
    [/\b(?:pip|python|python3|pytest|git|cd|ls|rm|mkdir|echo|cat|source|apt|pkg)\b/y, 'function'],
    [/\b\d+\b/y, 'number'],
    [/--?[A-Za-z][\w-]*/y, 'constant'],
  ],
  json: [
    [/"(?:[^"\\]|\\.)*"(?=\s*:)/y, 'variable'],
    [/"(?:[^"\\]|\\.)*"/y, 'string'],
    [/\b(?:true|false|null)\b/y, 'constant'],
    [/-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?/y, 'number'],
  ],
  markdown: [
    [/^#{1,6} [^\n]*/y, 'keyword'],
    [/`[^`\n]*`/y, 'string'],
    [/\*\*[^*\n]+\*\*/y, 'type'],
    [/^\s*[-*+] /y, 'control'],
  ],
  conf: [
    [/#[^\n]*/y, 'comment'],
    [/^[A-Za-z_][\w.-]*(?==)/y, 'variable'],
    [/[<>=!~]+/y, 'control'],
    [/\b\d[\d.]*\b/y, 'number'],
  ],
};

const SYNTAX_BY_EXT = {
  py: 'python', sh: 'sh', bash: 'sh', json: 'json', md: 'markdown', markdown: 'markdown',
  txt: 'conf', env: 'conf', example: 'conf', gitignore: 'conf', cfg: 'conf', toml: 'conf',
};

function syntaxOf(name) {
  const lower = String(name || '').toLowerCase();
  if (lower === 'requirements.txt' || lower.startsWith('.env')) return 'conf';
  const dot = lower.lastIndexOf('.');
  return SYNTAX_BY_EXT[dot >= 0 ? lower.slice(dot + 1) : lower] || null;
}

/** One line of code as coloured HTML. Lines are independent: no block state. */
function highlight(line, lang) {
  const rules = SYNTAX[lang];
  if (!rules) return esc(line);
  let out = '';
  let plain = '';
  let i = 0;
  while (i < line.length) {
    let hit = null;
    for (const [re, kind] of rules) {
      re.lastIndex = i;
      const m = re.exec(line);
      if (m && m.index === i && m[0].length) { hit = [m[0], kind]; break; }
    }
    if (hit) {
      if (plain) { out += esc(plain); plain = ''; }
      out += `<span style="color:var(--syn-${hit[1]})">${esc(hit[0])}</span>`;
      i += hit[0].length;
    } else {
      plain += line[i];
      i++;
    }
  }
  return out + esc(plain);
}

function highlightLines(lines, lang) {
  return lines.map((l) => highlight(l, lang));
}

/* -------------------------------------------------------------- markdown */

/** The transcript's markdown: paragraphs, lists, headings, fences, inline code. */
function md(src) {
  const lines = String(src || '').replace(/\r/g, '').split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const fence = line.match(/^```(\w*)/);
    if (fence) {
      const lang = fence[1] || '';
      const body = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
      i++;
      out.push(codeblock(body.join('\n'), lang));
      continue;
    }
    const heading = line.match(/^###?#? (.*)$/);
    if (heading) { out.push(`<h3>${inline(heading[1])}</h3>`); i++; continue; }
    if (/^\s*[-*] /.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*[-*] /.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*] /, ''));
      out.push(`<ul>${items.map((t) => `<li>${inline(t)}</li>`).join('')}</ul>`);
      continue;
    }
    if (/^\s*\d+[.)] /.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*\d+[.)] /.test(lines[i])) items.push(lines[i++].replace(/^\s*\d+[.)] /, ''));
      out.push(`<ol>${items.map((t) => `<li>${inline(t)}</li>`).join('')}</ol>`);
      continue;
    }
    if (!line.trim()) { i++; continue; }
    const para = [];
    while (i < lines.length && lines[i].trim() && !/^```/.test(lines[i]) && !/^\s*([-*]|\d+[.)]) /.test(lines[i]) && !/^#/.test(lines[i])) {
      para.push(lines[i++]);
    }
    out.push(`<p class="t-body">${inline(para.join('\n'))}</p>`);
  }
  return out.join('');
}

function inline(src) {
  const parts = [];
  const re = /`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)\s]+)\)/g;
  let last = 0;
  let m;
  while ((m = re.exec(src))) {
    parts.push(esc(src.slice(last, m.index)));
    if (m[1] !== undefined) parts.push(`<code class="ic">${esc(m[1])}</code>`);
    else if (m[2] !== undefined) parts.push(`<strong>${esc(m[2])}</strong>`);
    else parts.push(`<span class="link t-body">${esc(m[3])}</span>`);
    last = m.index + m[0].length;
  }
  parts.push(esc(src.slice(last)));
  return parts.join('');
}

function codeblock(code, lang) {
  const rows = highlightLines(code.split('\n'), SYNTAX[lang] ? lang : syntaxOf('x.' + lang));
  return `<div class="codeblock"><div class="codeblock-head">`
    + `<span class="t-mono-caption">${esc(lang || 'text')}</span>`
    + `<button class="ib xs" data-a="copy">${ic('copy', T.size.iconSmall)}</button></div>`
    + `<pre class="t-code">${rows.join('\n')}</pre></div>`;
}

/* ------------------------------------------------------------------ diff */

const HUNK = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$/;

/**
 * A unified diff as two gutters and a body, the way DiffView draws it: the
 * hunk header becomes a gap, because the numbers are already in the gutter.
 */
function diffRows(unified, lang) {
  const raw = [];
  let oldLine = 0, newLine = 0, numbered = false, oldLeft = 0, newLeft = 0;
  const inHunk = () => oldLeft > 0 || newLeft > 0;
  for (const rawLine of String(unified).replace(/\n$/, '').split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    if (line.startsWith('@@')) {
      const m = HUNK.exec(line);
      numbered = !!m;
      if (m) {
        oldLine = +m[1]; newLine = +m[3];
        oldLeft = +(m[2] || 1); newLeft = +(m[4] || 1);
      } else {
        oldLeft = newLeft = Infinity;
      }
      raw.push({ kind: 'hunk', text: m ? m[5] : '', o: null, n: null });
      continue;
    }
    if (line.startsWith('\\')) continue;
    if (inHunk()) {
      if (line.startsWith('+')) {
        raw.push({ kind: 'add', text: line.slice(1), o: null, n: numbered ? newLine : null });
        newLine++; newLeft--;
      } else if (line.startsWith('-')) {
        raw.push({ kind: 'rem', text: line.slice(1), o: numbered ? oldLine : null, n: null });
        oldLine++; oldLeft--;
      } else {
        raw.push({ kind: 'ctx', text: line.slice(1), o: numbered ? oldLine : null, n: numbered ? newLine : null });
        oldLine++; newLine++; oldLeft--; newLeft--;
      }
      continue;
    }
    if (line) raw.push({ kind: 'ctx', text: line, o: null, n: null });
  }
  if (raw.length && raw[0].kind === 'hunk' && raw.filter((r) => r.kind === 'hunk').length === 1 && !raw[0].text.trim()) {
    raw.shift();
  }
  const digits = (pick) => String(Math.max(1, ...raw.map((r) => pick(r) || 0))).length;
  const ow = digits((r) => r.o) * 7.2 + 10;
  const nw = digits((r) => r.n) * 7.2 + 10;

  // Each side is highlighted as its own file, so a removed line is coloured
  // in the old text's context and an added one in the new text's.
  const code = new Array(raw.length);
  const side = (skip) => {
    const idx = raw.map((r, i) => i).filter((i) => raw[i].kind !== skip && raw[i].kind !== 'hunk');
    highlightLines(idx.map((i) => raw[i].text), lang).forEach((html, k) => { code[idx[k]] = html; });
  };
  side('add');
  side('rem');

  const glyph = { add: '+', rem: '\u2212', ctx: ' ' };
  const rows = raw.map((r, i) => {
    if (r.kind === 'hunk') {
      return `<div class="dl hunk t-mono-caption">${esc(r.text || '\u22EF')}</div>`;
    }
    return `<div class="dl ${r.kind} t-code-small">`
      + `<span class="g tnum" style="width:${ow}px">${r.o || ''}</span>`
      + `<span class="g tnum" style="width:${nw}px">${r.n || ''}</span>`
      + `<span class="s">${glyph[r.kind]}</span>`
      + `<span class="c">${code[i] || esc(r.text)}</span></div>`;
  });
  return `<div class="diff"><div class="diff-rows">${rows.join('')}</div></div>`;
}

/* ------------------------------------------------------------------ ANSI */

const ANSI_NAMES = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white'];
const ANSI_BRIGHT = ['brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite'];

/** SGR colours, bold and dim — what the terminal pane understands. */
function ansi(text) {
  let out = '';
  let open = false;
  const style = { colour: null, bold: false, dim: false };
  const flush = () => { if (open) { out += '</span>'; open = false; } };
  const paint = () => {
    flush();
    const css = [];
    if (style.colour) css.push(`color:var(--ansi-${style.colour})`);
    if (style.bold) css.push('font-weight:700');
    if (style.dim) css.push('opacity:.6');
    if (css.length) { out += `<span style="${css.join(';')}">`; open = true; }
  };
  const re = /\u001b\[([\d;]*)m/g;
  let last = 0;
  let m;
  while ((m = re.exec(text))) {
    out += esc(text.slice(last, m.index));
    last = m.index + m[0].length;
    for (const part of (m[1] || '0').split(';')) {
      const n = +part;
      if (n === 0) { style.colour = null; style.bold = false; style.dim = false; }
      else if (n === 1) style.bold = true;
      else if (n === 2) style.dim = true;
      else if (n === 22) { style.bold = false; style.dim = false; }
      else if (n === 39) style.colour = null;
      else if (n >= 30 && n <= 37) style.colour = ANSI_NAMES[n - 30];
      else if (n >= 90 && n <= 97) style.colour = ANSI_BRIGHT[n - 90];
    }
    paint();
  }
  out += esc(text.slice(last));
  flush();
  return out;
}

function kebab(s) {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
}

/* =================================================================== data
 *
 * One project filled in as if the agent had just worked on it, so every
 * screen has something real to draw. Nothing here talks to a network.
 */

const FILES = {
  'bot/__init__.py': '',
  'bot/main.py': `import asyncio
import logging
import os

from aiogram import Bot, Dispatcher
from dotenv import load_dotenv

from bot.handlers import router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("bot")


async def main() -> None:
    load_dotenv()
    token = os.environ["BOT_TOKEN"]
    bot = Bot(token)
    dp = Dispatcher()
    dp.include_router(router)
    log.info("polling started")
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
`,
  'bot/handlers.py': `from aiogram import Router
from aiogram.filters import CommandStart
from aiogram.types import Message

router = Router()


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer("Привет! Пришлите текст, я повторю его.")


@router.message()
async def echo(message: Message) -> None:
    if message.text:
        await message.answer(message.text)
`,
  'tests/test_handlers.py': `import pytest
from aiogram.types import Chat, Message, User

from bot.handlers import echo


class Spy(Message):
    sent: list[str] = []

    async def answer(self, text: str, **kwargs) -> None:
        self.sent.append(text)


def message(text: str) -> Spy:
    return Spy.model_construct(
        message_id=1,
        chat=Chat(id=1, type="private"),
        from_user=User(id=1, is_bot=False, first_name="t"),
        text=text,
    )


@pytest.mark.asyncio
async def test_echo_repeats_text() -> None:
    m = message("привет")
    await echo(m)
    assert m.sent == ["привет"]
`,
  'requirements.txt': 'aiogram==3.31.0\npython-dotenv==1.2.1\n',
  '.env.example': '# Токен от @BotFather. Настоящий — только в .env, он в .gitignore.\nBOT_TOKEN=123456:replace-me\n',
  '.gitignore': '__pycache__/\n.env\n.venv/\n',
  'AGENTS.md': `# Эхо-бот

Запуск: \`python -m bot.main\`, токен — в \`.env\` (\`BOT_TOKEN\`).
Тесты: \`python -m pytest -q\`.

Отвечай по-русски, зависимости закрепляй по версиям.
`,
  'README.md': `# telegram-bot

Эхо-бот на aiogram 3: отвечает тем же текстом, что получил.

## Запуск

\`\`\`sh
cp .env.example .env
python -m bot.main
\`\`\`
`,
};

const TREE = [
  { name: 'bot', dir: true, open: true, children: [
    { name: '__init__.py', path: 'bot/__init__.py' },
    { name: 'handlers.py', path: 'bot/handlers.py', deco: 'a' },
    { name: 'main.py', path: 'bot/main.py', deco: 'm' },
  ] },
  { name: 'tests', dir: true, open: true, children: [
    { name: 'test_handlers.py', path: 'tests/test_handlers.py', deco: 'a' },
  ] },
  { name: '.env.example', path: '.env.example' },
  { name: '.gitignore', path: '.gitignore' },
  { name: 'AGENTS.md', path: 'AGENTS.md' },
  { name: 'README.md', path: 'README.md' },
  { name: 'requirements.txt', path: 'requirements.txt', deco: 'm' },
];

const MAIN_DIFF = `@@ -1,11 +1,15 @@
 import asyncio
+import logging
 import os
 
 from aiogram import Bot, Dispatcher
 from dotenv import load_dotenv
 
 from bot.handlers import router
 
+logging.basicConfig(
+    level=logging.INFO,
+    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
+)
+log = logging.getLogger("bot")
+
 
 async def main() -> None:
     load_dotenv()
@@ -14,6 +18,7 @@
     dp = Dispatcher()
     dp.include_router(router)
-    await dp.start_polling(bot)
+    log.info("polling started")
+    await dp.start_polling(bot)
`;

const PIP_OUT = `Collecting aiogram
  Downloading aiogram-3.31.0-py3-none-any.whl (696 kB)
Collecting python-dotenv
  Downloading python_dotenv-1.2.1-py3-none-any.whl (20 kB)
Collecting aiofiles<25.0.0,>=23.2.1 (from aiogram)
  Downloading aiofiles-24.1.0-py3-none-any.whl (15 kB)
Collecting pydantic<2.13,>=2.4.1 (from aiogram)
  Using cached pydantic-2.12.5-py3-none-any.whl (463 kB)
Installing collected packages: python-dotenv, aiofiles, pydantic, aiogram
Successfully installed aiofiles-24.1.0 aiogram-3.31.0 pydantic-2.12.5 python-dotenv-1.2.1`;

/** Turns, as ChatScreen lays them out: a user bubble, then the agent's parts. */
function transcript() {
  return [
    { role: 'user', text: 'Сделай эхо-бота на aiogram 3. Токен бери из .env' },
    { role: 'agent', parts: [
      { t: 'think', ms: 9400, text: 'aiogram 3 — это Router и Dispatcher, декораторы на роутере. Для /start нужен CommandStart(), для всего остального — message() без фильтра, иначе он перехватит и команды.\n\nТокен читаю через python-dotenv: load_dotenv() в main(), os.environ["BOT_TOKEN"]. Файл .env в .gitignore, рядом положу .env.example.\n\nСначала проверю, что aiogram ставится: на Android колёса pydantic собираются долго, лучше узнать это сразу.' },
      { t: 'text', md: 'Соберу минимальный бот: роутер с `/start` и обработчиком любого текста, токен из `.env` через `python-dotenv`. Сначала зависимости.' },
      { t: 'tool', name: 'pkg_install', target: 'python, python-pip', ms: 20400, ok: true,
        body: { kind: 'text', text: 'Установлено: python 3.12.8, python-pip 24.3.1 (из TUR). 2 пакета, 48,6 МБ.' } },
      { t: 'tool', name: 'sh_exec', target: '$ pip install aiogram python-dotenv', ms: 18200, ok: true,
        body: { kind: 'term', text: PIP_OUT } },
      { t: 'tool', name: 'fs_write', target: 'bot/handlers.py', ms: 120, ok: true, add: 15, rem: 0,
        body: { kind: 'diff', file: 'bot/handlers.py', unified: newFileDiff('bot/handlers.py') } },
      { t: 'tool', name: 'fs_write', target: 'bot/main.py', ms: 96, ok: true, add: 19, rem: 0 },
      { t: 'tool', name: 'fs_write', target: 'requirements.txt', ms: 61, ok: true, add: 2, rem: 0 },
      { t: 'tool', name: 'sh_exec', target: '$ python -m py_compile bot/main.py bot/handlers.py', ms: 1240, ok: true,
        body: { kind: 'term', text: '' } },
      { t: 'text', md: 'Готово. `bot/handlers.py` — роутер, `bot/main.py` — запуск, токен берётся из `.env`.\n\n```sh\ncp .env.example .env   # вписать BOT_TOKEN\npython -m bot.main\n```\n\nОбработчик без фильтра стоит **последним**: иначе он перехватил бы и `/start`.' },
    ] },
    { role: 'user', text: 'Добавь логирование и тест на /start' },
    { role: 'agent', parts: [
      { t: 'think', ms: 4100, text: 'Логирование — basicConfig в main.py, уровень INFO, формат с временем и именем логгера. Один log.info перед поллингом, чтобы было видно, что бот поднялся.\n\nТест: aiogram-овский Message — pydantic-модель, живой объект собрать неудобно, поэтому model_construct и подменённый answer. pytest-asyncio нужен для async-теста.' },
      { t: 'tasks', done: 3, items: [
        { text: 'Настроить logging в bot/main.py', state: 'done' },
        { text: 'Написать тест на эхо-обработчик', state: 'done' },
        { text: 'Запустить pytest', state: 'done' },
      ] },
      { t: 'tool', name: 'fs_edit', target: 'bot/main.py', ms: 180, ok: true, open: true,
        body: { kind: 'diff', file: 'bot/main.py', unified: MAIN_DIFF } },
      { t: 'tool', name: 'fs_write', target: 'tests/test_handlers.py', ms: 104, ok: true, add: 28, rem: 0,
        body: { kind: 'diff', file: 'tests/test_handlers.py', unified: newFileDiff('tests/test_handlers.py') } },
      { t: 'tool', name: 'sh_exec', target: '$ pip install pytest pytest-asyncio && python -m pytest -q', ms: 14600, ok: true,
        body: { kind: 'term', text: 'Successfully installed pytest-8.4.2 pytest-asyncio-1.3.0\n\u001b[32m.\u001b[0m                                                                  [100%]\n\u001b[32m1 passed\u001b[0m in 0.42s' } },
      { t: 'text', md: 'Логи включены: `basicConfig` с уровнем INFO и строка `polling started` перед поллингом. Тест `test_echo_repeats_text` подменяет `answer` и проверяет, что текст возвращается как есть — `1 passed`.' },
    ] },
    { role: 'user', text: 'Бот иногда отвечает на одно сообщение дважды. Найди причину и почини' },
    { role: 'agent', parts: [
      { t: 'think', ms: 3800, text: 'Дважды — значит апдейт проходит обработчик два раза: роутер подключён дважды, polling запущен два раза или два обработчика ловят один текст. Чтобы не тащить все файлы в свой контекст, поиск поручу субагенту только для чтения, а исправлю сам.' },
      { t: 'text', md: 'Поиск причины поручу субагенту: он только читает, и чтение файлов не займёт мой контекст.' },
      structuredClone(SUB_SAMPLE),
      { t: 'tool', name: 'fs_edit', target: 'bot/main.py', ms: 140, ok: true,
        body: { kind: 'diff', file: 'bot/main.py', unified: DEDUP_DIFF } },
      { t: 'tool', name: 'sh_exec', target: '$ python -m pytest -q', ms: 2900, ok: true,
        body: { kind: 'term', text: '\u001b[32m.\u001b[0m                                                                  [100%]\n\u001b[32m1 passed\u001b[0m in 0.40s' } },
      { t: 'text', md: 'Причина — роутер был подключён дважды: `dp.include_router(router)` стоял в `bot/main.py` две строки подряд, и каждое сообщение проходило обработчики два раза. Убрал вторую строку, тесты зелёные.' },
    ] },
  ];
}

/*
 * A subagent's run, as its card shows it: the task the main agent wrote,
 * every step it took (its reasoning, its tool calls, anything it said on the
 * way) and the answer it returned. An Explorer only reads, so it never asks.
 */
const SUB_SAMPLE = {
  t: 'sub', agent: 'explorer', ok: true, ms: 21800,
  prompt: 'Бот на aiogram 3 иногда отвечает на одно сообщение дважды. Найди причину. Проверь, сколько раз подключаются роутеры (include_router) и запускается polling, не создаётся ли Dispatcher второй раз и нет ли двух обработчиков, которые ловят один и тот же текст. Ничего не меняй. Ответь коротко: причина, файл и строка, как исправить.',
  files: ['bot/main.py', 'bot/handlers.py'],
  steps: [
    { t: 'tool', name: 'project_tree', ms: 40, ok: true,
      body: { kind: 'text', text: 'bot/\n  __init__.py\n  handlers.py\n  main.py\ntests/\n  test_handlers.py\n.env.example\nREADME.md\nrequirements.txt' } },
    { t: 'tool', name: 'fs_read', target: 'bot/main.py', ms: 12, ok: true },
    { t: 'tool', name: 'grep', target: 'include_router', ms: 38, ok: true,
      body: { kind: 'text', text: 'bot/main.py:22:    dp.include_router(router)\nbot/main.py:23:    dp.include_router(router)' } },
    { t: 'think', ms: 2100, text: 'Две одинаковые строки подряд: каждый апдейт пройдёт роутер дважды. Проверю, что Dispatcher создаётся один раз и в handlers.py нет второго обработчика того же текста.' },
    { t: 'tool', name: 'fs_read', target: 'bot/handlers.py', ms: 9, ok: true },
    { t: 'tool', name: 'grep', target: 'Dispatcher\\(|start_polling', ms: 31, ok: true,
      body: { kind: 'text', text: 'bot/main.py:21:    dp = Dispatcher()\nbot/main.py:25:    await dp.start_polling(bot)' } },
  ],
  answer: 'Причина — роутер подключён дважды: `dp.include_router(router)` стоит в `bot/main.py` на строках 22 и 23, поэтому каждое сообщение проходит обработчики два раза.\n\nDispatcher создаётся один раз (строка 21), polling запускается один раз (строка 25), обработчики в `bot/handlers.py` не пересекаются.\n\nИсправление: удалить строку 23.',
};

const DEDUP_DIFF = `@@ -20,6 +20,5 @@
     bot = Bot(token)
     dp = Dispatcher()
     dp.include_router(router)
-    dp.include_router(router)
     log.info("polling started")
     await dp.start_polling(bot)`;

/** A new file shown as a diff: every line added, numbered from 1. */
function newFileDiff(path) {
  const lines = FILES[path].replace(/\n$/, '').split('\n');
  return `@@ -0,0 +1,${lines.length} @@\n` + lines.map((l) => '+' + l).join('\n');
}

function diffStats(unified) {
  let add = 0, rem = 0;
  for (const line of unified.split('\n')) {
    if (line.startsWith('+')) add++;
    else if (line.startsWith('-')) rem++;
  }
  return { add, rem };
}

const AGENT_TRANSCRIPT = `\u001b[32m~/projects/telegram-bot\u001b[0m $ \u001b[1mpip install aiogram python-dotenv\u001b[0m
${PIP_OUT}
\u001b[2m[exit 0 · 18,2 с]\u001b[0m

\u001b[32m~/projects/telegram-bot\u001b[0m $ \u001b[1mpython -m py_compile bot/main.py bot/handlers.py\u001b[0m
\u001b[2m[exit 0 · 1,2 с]\u001b[0m

\u001b[32m~/projects/telegram-bot\u001b[0m $ \u001b[1mpip install pytest pytest-asyncio && python -m pytest -q\u001b[0m
Successfully installed pytest-8.4.2 pytest-asyncio-1.3.0
\u001b[32m.\u001b[0m                                                                  [100%]
\u001b[32m1 passed\u001b[0m in 0.42s
\u001b[2m[exit 0 · 14,6 с]\u001b[0m

\u001b[32m~/projects/telegram-bot\u001b[0m $ `;

const SHELL_START = `\u001b[2mApkCLI shell · bash 5.2 · aarch64-linux-android\u001b[0m
\u001b[32m~/projects/telegram-bot\u001b[0m $ \u001b[1mpython --version\u001b[0m
Python 3.12.8
\u001b[32m~/projects/telegram-bot\u001b[0m $ `;

const SHELL_CANNED = {
  ls: 'AGENTS.md  README.md  bot  requirements.txt  tests',
  'ls -a': '.  ..  .env.example  .gitignore  AGENTS.md  README.md  bot  requirements.txt  tests',
  pwd: '/data/data/dev.apkcli/files/home/projects/telegram-bot',
  'python --version': 'Python 3.12.8',
  'pip list': 'Package         Version\n--------------- -------\naiofiles        24.1.0\naiogram         3.31.0\npydantic        2.12.5\npytest          8.4.2\npython-dotenv   1.2.1',
  'git status': 'bash: git: command not found',
  whoami: 'u0_a312',
};

/*
 * What a connection answers with when the app asks it for models.
 *
 * Sample data, and deliberately not maintained by hand: the app ships no
 * model list at all, it reads each endpoint's own GET /models once a key is
 * saved. These rows exist so the screens have something to draw, and
 * tools/gen_catalogue.py regenerates them by rule -- newest N that call tools
 * and return text -- so no version number is written here on purpose.
 *
 * Fields are the ones OpenCode's provider config carries, which it takes from
 * models.dev: id, display name, context and output limits, images, reasoning.
 * They map onto the app's ModelSpec as context -> contextWindow,
 * output -> maxOutputTokens. The numbers are each model's own specification,
 * so a gateway in front of one may allow less.
 */
const CATALOGUE = {
  anthropic: [
    { id: "claude-haiku-5-5", name: "Claude Haiku 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-sonnet-5-5", name: "Claude Sonnet 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-opus-5-5", name: "Claude Opus 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-fable-5-1", name: "Claude Fable 5.1", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-opus-5", name: "Claude Opus 5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-sonnet-5", name: "Claude Sonnet 5", context: 1000000, output: 128000, vision: true, reasoning: true },
  ],
  openai: [
    { id: "gpt-6.1-sol", name: "GPT-6.1 Sol", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "gpt-6-sol", name: "GPT-6 Sol", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "gpt-6-luna", name: "GPT-6 Luna", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "gpt-6-astra", name: "GPT-6 Astra", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "gpt-daybreak-red-latest", name: "Daybreak Red", context: 400000, output: 128000, vision: true, reasoning: true },
    { id: "gpt-daybreak-blue-latest", name: "Daybreak Blue", context: 1050000, output: 128000, vision: true, reasoning: true },
  ],
  openrouter: [
    { id: "openai/gpt-6.1-sol-pro", name: "GPT-6.1 Sol Pro", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "openai/gpt-6.1-sol", name: "GPT-6.1 Sol", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "anthropic/claude-sonnet-5.5", name: "Claude Sonnet 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "z-ai/glm-5.3-prime", name: "GLM 5.3 Prime", context: 1000000, output: 131072, reasoning: true },
    { id: "qwen/qwen3.8-max-prime", name: "Qwen 3.8 Max Prime", context: 1000000, output: 131072, vision: true, reasoning: true },
    { id: "openai/gpt-6-sol-pro", name: "GPT-6 Sol Pro", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "openai/gpt-6-sol", name: "GPT-6 Sol", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "openai/gpt-6-luna-pro", name: "GPT-6 Luna Pro", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "openai/gpt-6-luna", name: "GPT-6 Luna", context: 1050000, output: 128000, vision: true, reasoning: true },
    { id: "anthropic/claude-opus-5.5", name: "Claude Opus 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
  ],
  kiro: [
    { id: "claude-haiku-5-5", name: "Claude Haiku 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-sonnet-5-5", name: "Claude Sonnet 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "claude-opus-5-5", name: "Claude Opus 5.5", context: 1000000, output: 128000, vision: true, reasoning: true },
    { id: "glm-5.3-prime", name: "GLM 5.3 Prime", context: 1000000, output: 131072, reasoning: true },
    { id: "qwen3.8-max-prime", name: "Qwen 3.8 Max Prime", context: 1000000, output: 131072, vision: true, reasoning: true },
    { id: "glm-5.3-flashx", name: "GLM 5.3 FlashX", context: 1048576, output: 131072, vision: true, reasoning: true },
    { id: "qwen3.8-omni-flash", name: "Qwen3.8 Omni Flash", context: 1000000, output: 131072, vision: true, reasoning: true },
  ],
  custom: [],
};

/*
 * Connection schemes.
 *
 * One per service, each carrying everything the app needs to reach it, so the
 * only thing left for the user is the key. Nothing here is asked for twice:
 * the URL, the wire format, the header the credential goes in and the
 * attribution headers are properties of the service, not decisions, so the
 * form for a scheme is a single field. Only "Кастомный эндпоинт" asks more,
 * because there nothing is known.
 *
 * `check` is the route that validates a credential for the least money, and
 * `prefix` is what that credential looks like, so a paste can be caught
 * before a request is spent on it. Verified against each service's own docs
 * in 2026; see docs/PROVIDERS.md for the full scheme and the sources.
 *
 * The app's copy is ConnectionScheme.kt; the notes are its strings
 * (note_anthropic and the like), word for word.
 */
const PRESETS = [
  {
    id: 'anthropic', name: 'Anthropic', icon: 'hubot', sub: 'Claude напрямую',
    url: 'https://api.anthropic.com/v1', protocol: 'anthropic',
    field: 'API-ключ', prefix: 'sk-ant-', hint: 'sk-ant-api03-…',
    where: 'console.anthropic.com → API keys',
    check: 'GET /v1/models',
    catalogue: 'full',
    note: 'Ключ уходит в x-api-key, заголовок anthropic-version: 2023-06-01 добавляется сам. '
      + 'Список моделей приходит с лимитами и с тем, как каждая думает: с Claude 4.6 — адаптивно, '
      + 'с уровнем в output_config.effort, у более ранних — по бюджету токенов.',
  },
  {
    id: 'openai', name: 'OpenAI', icon: 'sparkle', sub: 'GPT напрямую',
    url: 'https://api.openai.com/v1', protocol: 'openai',
    field: 'API-ключ', prefix: 'sk-', hint: 'sk-proj-…',
    where: 'platform.openai.com → API keys',
    check: 'GET /v1/models',
    catalogue: 'ids',
    note: 'Ключ уходит в Authorization: Bearer. Лимит ответа задаётся max_completion_tokens: '
      + 'рассуждающие модели max_tokens отклоняют. Модели семейств pro и codex работают только '
      + 'через /responses — для них нужен отдельный адаптер, и в списке они помечены.',
  },
  {
    id: 'openrouter', name: 'OpenRouter', icon: 'globe', sub: 'Один ключ на все модели',
    url: 'https://openrouter.ai/api/v1', protocol: 'openai',
    field: 'API-ключ', prefix: 'sk-or-v1-', hint: 'sk-or-v1-…',
    where: 'openrouter.ai/keys',
    check: 'GET /api/v1/key',
    catalogue: 'full',
    // Attribution; it labels the traffic in OpenRouter's dashboard. No
    // HTTP-Referer: it would have to be a site the app owns.
    headers: 'X-OpenRouter-Title: ApkCLI\nX-Title: ApkCLI',
    note: 'Список моделей приходит с лимитами: окно контекста у всех моделей, лимит ответа почти '
      + 'у всех, уровни рассуждения примерно у половины — для остальных действует уровень по '
      + 'умолчанию. Список доступен и без ключа, поэтому ключ проверяется отдельно, через /key.',
  },
  {
    id: 'kiro', name: 'Kiro', icon: 'server-environment', sub: 'По токену аккаунта',
    url: 'https://q.us-east-1.amazonaws.com', protocol: 'kiro',
    field: 'Токен аккаунта', prefix: 'aorAAAAAG', hint: 'aorAAAAAG…',
    where: '~/.aws/sso/cache/kiro-auth-token.json → refreshToken · Builder ID: + <clientIdHash>.json → clientId, clientSecret',
    check: 'POST refreshToken · oidc/token',
    catalogue: 'full',
    note: 'Публичного API у Kiro нет: внутри это Amazon Q Developer, и приложение обращается к нему '
      + 'так же, как Kiro Account Manager. Для входа через Google или GitHub нужен только refreshToken; '
      + 'для Builder ID — ещё clientId и clientSecret: вставьте их как JSON или в формате экспорта '
      + 'менеджера. Токен обменивается на access token, модели приходят из ListAvailableModels, ответ — '
      + 'в формате AWS event-stream. Обмен может выдать новый токен обновления, и тогда Kiro IDE со '
      + 'старым токеном разлогинится — и наоборот. AWS такое использование не одобряет.',
  },
  {
    id: 'custom', name: 'Кастомный эндпоинт', icon: 'server',
    sub: 'vLLM, llama.cpp, Ollama, LM Studio, свой прокси',
    url: '', protocol: 'openai', protocols: ['openai', 'anthropic'], custom: true,
    field: 'API-ключ', prefix: '', hint: 'Если сервер его требует',
    catalogue: 'ids',
    note: 'Базовый URL до /v1, маршрут приложение добавит само. /chat/completions есть у всех; '
      + '/messages — у Ollama от 0.14 и LM Studio, у vLLM и llama.cpp обычно нет. '
      + 'Локальному серверу ключ чаще не нужен, по локальной сети http допустим.',
  },
];

/*
 * Where a connection's model list comes from, which is not the same question
 * for every scheme. Probed with tools/probe_models.py; see docs/PROVIDERS.md.
 *
 *  full  the list arrives with the limits in it: OpenRouter, Anthropic's
 *        Models API, and Kiro's ListAvailableModels (on q.{region}, paged by
 *        nextToken, each model with its tokenLimits)
 *  ids   the route works but answers with ids, so the window is a cautious
 *        default until an overflow error says the real one (OpenAI, most
 *        local servers)
 *
 * The app's copy is ModelSource; the texts are model_source_full and _ids.
 */
const CATALOGUE_SOURCE = {
  full: 'Список и лимиты пришли от сервера.',
  ids: 'Сервер отдаёт только id моделей, поэтому окно контекста — осторожное значение, пока ошибка переполнения не сообщит настоящее.',
};

/*
 * The wire format: the route plus the adapter that speaks it.
 *
 * The adapter is where reasoning lives — a level for OpenAI, an effort for
 * Anthropic whose signed `thinking` blocks have to go back unchanged, and for
 * Kiro an AWS event-stream that is not SSE at all. A scheme already knows
 * which one it needs, so this is only ever a question for a custom URL.
 */
const PROTOCOLS = [
  {
    id: 'openai', name: 'OpenAI', path: '/chat/completions',
    effect: 'Рассуждение уровнем в reasoning_effort, ключ в Authorization: Bearer.',
  },
  {
    id: 'anthropic', name: 'Anthropic', path: '/messages',
    effect: 'Рассуждение через output_config.effort; подписанные блоки thinking возвращаются без изменений. Ключ в x-api-key.',
  },
  {
    id: 'kiro', name: 'Kiro', path: '/generateAssistantResponse',
    effect: 'Amazon Q Developer: access token из токена аккаунта, ответ в AWS event-stream.',
  },
];

/*
 * The agent's mode: when it asks before acting. Three, and only these.
 * Reading and searching never ask in any of them.
 */
const AGENTS = [
  { id: 'confirm', name: 'Confirm',
    desc: 'Спрашивает перед каждым изменением: записью и удалением файлов, командами, пакетами. Читает и ищет сам.' },
  { id: 'rules', name: 'From Rules',
    desc: 'Спрашивает только о том, что не отмечено в правилах.' },
  // Honest about what an undo covers: FsDeleteTool snapshots a text file,
  // never a folder or a binary.
  { id: 'bypass', name: 'Bypass Mode',
    desc: 'Ничего не спрашивает и доводит задачу до конца. Правки файлов можно откатить, удалённые папки — нет.' },
];

/*
 * The rules From Rules follows, in Settings: what is ticked goes ahead
 * without asking, the rest waits for «Разрешить». Each is a group of the
 * app's tools (ToolRules in the app); a read never asks and is not listed.
 */
const RULES = [
  { id: 'edit', title: 'Правка файлов', sub: 'Создание, запись и правка' },
  { id: 'delete', title: 'Удаление файлов', sub: 'Удаление, перемещение и откат правок' },
  { id: 'commands', title: 'Команды', sub: 'Команды в терминале, сборка и тесты' },
  { id: 'background', title: 'Фоновые процессы', sub: 'Запуск и остановка долгих команд' },
  { id: 'packages', title: 'Пакеты', sub: 'Установка и удаление' },
  { id: 'subagents', title: 'Субагенты', sub: 'Поручения другому агенту; его правки идут по тем же правилам' },
];

/*
 * Effort levels, as both OpenAI and Anthropic spell them in 2026. Which ones
 * a model accepts comes from the endpoint, so the list is filtered per model
 * rather than assumed; an unsupported level is moved to the nearest one.
 */
const REASONING = [
  { id: 'none', name: 'Выкл', desc: 'Отвечает без рассуждения' },
  { id: 'minimal', name: 'Минимальное', desc: 'Несколько токенов на план' },
  { id: 'low', name: 'Низкое', desc: 'Быстрые ответы, меньше токенов' },
  { id: 'medium', name: 'Среднее', desc: 'Баланс скорости и точности' },
  { id: 'high', name: 'Высокое', desc: 'Держит длинные задачи' },
  { id: 'xhigh', name: 'Очень высокое', desc: 'Для глубокого поиска по коду и цепочек вызовов' },
  { id: 'max', name: 'Максимальное', desc: 'Всё, что модель может, с заметной задержкой' },
];

/* ================================================================== state */

const S = {
  route: { name: 'home' },
  first: false,
  agent: 'bypass',
  // From Rules: true goes ahead without asking.
  rules: { edit: true, delete: false, commands: false, background: false, packages: false, subagents: true },
  model: CATALOGUE.openrouter[0].id,
  reasoning: 'high',
  language: 'system',
  split: 'auto',
  endpoints: [
    {
      preset: 'openrouter', name: 'OpenRouter', url: 'https://openrouter.ai/api/v1',
      protocol: 'openai', headers: 'X-OpenRouter-Title: ApkCLI\nX-Title: ApkCLI',
      catalogue: 'full', key: true, models: CATALOGUE.openrouter, active: true,
    },
  ],
  projects: [
    { name: 'telegram-bot', min: 2, files: 9, note: 'Эхо-бот на aiogram' },
    { name: 'fastapi-todo', min: 26 * 60, files: 24, note: 'API со SQLite' },
    { name: 'portfolio-site', min: 3 * 24 * 60, files: 61, note: 'Статика, Eleventy' },
    { name: 'scraper', min: 9 * 24 * 60, files: 7, note: 'Парсер цен' },
  ],
  project: null,
  ws: {},
  sheet: null,
  menu: null,
  toast: null,
  search: '',
  modelSearch: '',
  // The history sheet: its search, the chat a rename or delete is for, and
  // the name being typed (null until the field is touched).
  historySearch: '',
  chatTarget: null,
  rename: null,
  clone: { input: '', error: null, step: 0, bytes: 0, files: 0 },
  create: { input: '', error: null },
  form: null,
  note: null,
  focus: null,
  scroll: {},
};

/** Per-project workspace state, created on first visit. */
function W(name) {
  if (!S.ws[name]) {
    const sample = name === 'telegram-bot';
    const chats = sample ? sampleChats() : [emptyChat()];
    S.ws[name] = {
      tab: 'agent',
      // The project's chats; the open one's turns are `chat`.
      chats,
      chatId: chats[0].id,
      chat: chats[0].turns,
      // The search in the open chat while it is on: { q, i, count, shown }.
      find: null,
      open: {},
      busy: false,
      live: null,
      follow: true,
      tree: sample ? TREE.map(cloneNode) : [],
      tabs: sample ? ['bot/main.py'] : [],
      active: sample ? 'bot/main.py' : null,
      selected: sample ? 'bot/main.py' : null,
      cursor: 11,
      filesView: 'tree',
      // The user's shells, "Shell #N", and the chat's transcript of the
      // agent's commands, "Агент", numbered apart: called "Shell #1" beside
      // the user's "Shell #2", it read as a shell the agent declined to use.
      // agentSession points at the transcript; any of them can be closed.
      sessions: sample
        ? [
          { id: 's1', n: 1, agent: true, out: AGENT_TRANSCRIPT, running: false },
          { id: 's2', n: 1, out: SHELL_START, running: false },
        ]
        : [{ id: 's1', n: 1, out: SHELL_START, running: false }],
      session: 's1',
      agentSession: sample ? 's1' : null,
      changes: chats[0].changes,
      memory: sample
        ? [
          { scope: 'project', key: 'запуск', value: 'python -m bot.main, токен в .env (BOT_TOKEN).' },
          { scope: 'project', key: 'токен', value: 'Настоящий токен только в .env, в коде его не писать.' },
          { scope: 'global', key: 'язык', value: 'Отвечать по-русски, комментарии в коде тоже по-русски.' },
        ]
        : [{ scope: 'global', key: 'язык', value: 'Отвечать по-русски, комментарии в коде тоже по-русски.' }],
      draft: '',
      attachments: [],
      context: chats[0].context,
    };
  }
  return S.ws[name];
}

function cloneNode(node) {
  return node.children ? Object.assign({}, node, { children: node.children.map(cloneNode) }) : Object.assign({}, node);
}

function ws() {
  return W(S.project);
}

/* ------------------------------------------------------------------ chats */

/*
 * A project's chats. "+" starts a new one and the old one stays: the history
 * sheet lists them newest first, to go back to, rename or delete. Each is a
 * session of its own in the app (ConversationStore): its transcript, its
 * checkpoints, its pending changes, its context. A chat left empty is not
 * kept once another is opened; it would only be one more "Новый чат".
 *
 * The open chat's turns, changes and context live on the workspace (w.chat,
 * w.changes, w.context), where the rest of the prototype reads them; they go
 * back into its entry when another chat is opened.
 */

/** A timestamp [days] calendar days back at hh:mm, for the sample history. */
function daysAgoAt(days, hh, mm) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  d.setHours(hh, mm, 0, 0);
  return d.getTime();
}

/** One past chat of the sample: its exchanges, enough to open and read. */
function pastChat(id, at, exchanges, context) {
  const turns = [];
  for (const [ask, answer] of exchanges) {
    turns.push({ role: 'user', text: ask });
    turns.push({ role: 'agent', parts: [{ t: 'text', md: answer }] });
  }
  return { id, at, title: chatTitle(exchanges[0][0]), turns, changes: null, context };
}

/** telegram-bot's chats: the open one with the long transcript, and six before it. */
function sampleChats() {
  return [
    { id: 'c1', at: Date.now() - 2 * 60000, title: chatTitle('Сделай эхо-бота на aiogram 3. Токен бери из .env'),
      turns: transcript(), changes: { files: 4, add: 64, rem: 1 }, context: 34200 },
    pastChat('c2', Date.now() - 3 * 3600000 - 10 * 60000, [
      ['Почему бот молчит в группе?',
        'В группах у бота по умолчанию включён privacy mode: он видит только команды и ответы на свои сообщения. Выключите его у @BotFather — `/setprivacy` → **Disable**, — потом удалите бота из группы и добавьте снова.'],
      ['А без BotFather можно?',
        'Нет: privacy mode — настройка бота на стороне Telegram, из кода её не поменять. Другой путь — сделать бота администратором группы, администраторы видят все сообщения.'],
    ], 9800),
    pastChat('c3', daysAgoAt(1, 18, 40), [
      ['Добавь команду /help со списком команд',
        'Добавил `/help` в `bot/handlers.py`: бот отвечает списком из `BOT_COMMANDS`, и тот же список уходит в `set_my_commands`, так что меню Telegram и `/help` не разойдутся.'],
    ], 12400),
    pastChat('c4', daysAgoAt(1, 11, 5), [
      ['Объясни, как устроен проект',
        'Точка входа — `bot/main.py`: читает токен из `.env`, создаёт `Bot` и `Dispatcher`, подключает роутер и запускает поллинг. Обработчики — в `bot/handlers.py`, тесты — в `tests/`.'],
    ], 7300),
    pastChat('c5', daysAgoAt(4, 20, 15), [
      ['Перенеси токен в .env и убери его из кода',
        'Токен теперь читается из `.env` через `python-dotenv`, из `bot/main.py` он убран. Рядом лежит `.env.example` с пустым `BOT_TOKEN`.'],
      ['Проверь, что .env в .gitignore',
        'Да, `.env` в `.gitignore` с первой строки, в истории он не появлялся.'],
    ], 11100),
    pastChat('c6', daysAgoAt(9, 14, 30), [
      ['Настрой ruff и поправь замечания',
        'Добавил `ruff` в `requirements-dev.txt` и секцию `[tool.ruff]` в `pyproject.toml`. Поправил 11 замечаний: неиспользуемые импорты и порядок импортов. `ruff check .` — чисто.'],
    ], 15800),
    pastChat('c7', daysAgoAt(17, 10, 0), [
      ['Сделай Dockerfile для бота',
        'Dockerfile на `python:3.12-slim`: зависимости ставятся отдельным слоем, бот запускается от непривилегированного пользователя. Токен передаётся через `--env-file .env`.'],
    ], 8600),
  ];
}

/** AgentLoop.deriveTitle: the first line of the first message, at most 60 characters. */
function chatTitle(text) {
  const line = String(text || '').split('\n').map((l) => l.trim()).find(Boolean) || 'Изображение';
  return line.length <= 60 ? line : line.slice(0, 57) + '…';
}

function currentChat(w) {
  return w.chats.find((c) => c.id === w.chatId) || null;
}

/** The open chat's turns are the workspace's; the others keep their own. */
function turnsOf(w, c) {
  return c.id === w.chatId ? w.chat : c.turns;
}

/** The last request in a chat, which session.list returns as its preview. */
function lastRequest(turns) {
  for (let i = turns.length - 1; i >= 0; i--) if (turns[i].role === 'user' && turns[i].text) return turns[i].text;
  return '';
}

function byRecent(chats) {
  return chats.slice().sort((a, b) => b.at - a.at);
}

/** The open chat's live state back into its entry, before another is opened. */
function stashChat(w) {
  const c = currentChat(w);
  if (!c) return;
  c.turns = w.chat;
  c.changes = w.changes;
  c.context = w.context;
}

/** Open chat [id]. Not while a turn runs: the turn belongs to the open chat. */
function openChat(w, id) {
  const next = w.chats.find((c) => c.id === id);
  if (!next || w.busy || next.id === w.chatId) return;
  stashChat(w);
  const left = currentChat(w);
  if (left && !left.turns.length) w.chats = w.chats.filter((c) => c !== left);
  w.chatId = next.id;
  w.chat = next.turns;
  w.changes = next.changes || null;
  w.context = next.context || 4100;
  w.find = null;
  w.open = {};
  w.draft = '';
  w.attachments = [];
  // A chat opens at its end, as the app's does.
  delete S.scroll.chat;
}

function emptyChat() {
  return { id: 'c' + Date.now().toString(36) + Math.floor(Math.random() * 1e4), at: Date.now(), title: '', turns: [], changes: null, context: 4100 };
}

/** "+": a new chat, unless the open one is new already. */
function newChat(w) {
  if (w.busy || !w.chat.length) return;
  const chat = emptyChat();
  w.chats.push(chat);
  openChat(w, chat.id);
}

/** Delete chat [id]; deleting the open one opens the most recent left, or a new one. */
function deleteChat(w, id) {
  if (!w.chats.some((c) => c.id === id) || (w.busy && id === w.chatId)) return;
  const wasOpen = id === w.chatId;
  w.chats = w.chats.filter((c) => c.id !== id);
  if (!wasOpen) return;
  w.chatId = null;
  const next = byRecent(w.chats)[0] || emptyChat();
  if (!w.chats.includes(next)) w.chats.push(next);
  openChat(w, next.id);
}

const MONTHS = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

/** Calendar days between [ts] and today: 0 today, 1 yesterday. */
function daysBack(ts) {
  const day = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  return Math.round((day(Date.now()) - day(ts)) / 86400000);
}

/** "13:56" under Сегодня and Вчера, "5 окт" under Ранее, with the year when it is another one. */
function chatWhen(ts) {
  const d = new Date(ts);
  if (daysBack(ts) <= 1) return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  const year = d.getFullYear() === new Date().getFullYear() ? '' : ` ${d.getFullYear()}`;
  return `${d.getDate()} ${MONTHS[d.getMonth()]}${year}`;
}

function shellName(session) {
  if (session.agent) return session.n <= 1 ? 'Агент' : `Агент #${session.n}`;
  return `Shell #${session.n}`;
}

/**
 * A new shell, or with [agent] the chat's transcript, taking the lowest
 * number free among its own kind so the names stay short.
 */
function newShell(w, agent) {
  let n = 1;
  while (w.sessions.some((s) => !s.agent === !agent && s.n === n)) n++;
  const session = { id: 's' + Date.now().toString(36), n, out: SHELL_START, running: false };
  if (agent) session.agent = true;
  w.sessions.push(session);
  return session;
}

/** The connection requests go to. */
function conn() {
  return S.endpoints.find((e) => e.active) || S.endpoints[0] || null;
}

function modelOf(id) {
  const c = conn();
  return (c ? c.models : []).find((m) => m.id === id) || null;
}

function shortModel(id) {
  return String(id).split('/').pop();
}

/** "контекст 200k · ответ 64k · изображения" — the ModelSpec in one line. */
function modelLine(m) {
  const parts = [`контекст ${tokens(m.context)}`, `ответ ${tokens(m.output)}`];
  if (m.vision) parts.push('изображения');
  if (responsesOnly(m)) parts.push('только /responses');
  return parts.join(' · ');
}

/**
 * OpenAI serves its pro and codex families only through /responses, which is
 * a different adapter, so such a model is flagged rather than offered as if
 * it would work.
 */
function responsesOnly(m) {
  const c = conn();
  return !!c && c.preset === 'openai' && /-(pro|codex)\b/.test(m.id);
}

/** "Без подтверждения: правка файлов, субагенты", or that everything asks. */
function rulesSummary() {
  const free = RULES.filter((r) => S.rules[r.id]).map((r) => r.title.toLowerCase());
  return free.length ? `Без подтверждения: ${free.join(', ')}` : 'Спрашивает обо всём';
}

/** Whether a call of [rule]'s kind waits for «Разрешить» in the current mode. */
function asks(rule) {
  if (S.agent === 'bypass') return false;
  if (S.agent === 'rules') return !S.rules[rule];
  return true;
}

function agentOf(id) {
  return AGENTS.find((a) => a.id === id) || AGENTS[0];
}

function reasoningOf(id) {
  return REASONING.find((r) => r.id === id) || REASONING[0];
}

const TOOL_NAMES = {
  fs_read: 'Чтение файла', fs_write: 'Запись файла', fs_edit: 'Правка файла',
  fs_delete: 'Удаление', fs_move: 'Перемещение', fs_list: 'Содержимое папки',
  fs_glob: 'Поиск файлов', project_tree: 'Дерево проекта', grep: 'Поиск по коду',
  sh_exec: 'Команда', sh_background: 'Фоновая задача', build_run: 'Сборка',
  test_run: 'Тесты', task_list: 'План', memory_write: 'Запомнить',
  web_fetch: 'Открыть страницу', pkg_install: 'Установка пакетов',
};

const TOOL_ICONS = {
  fs_read: 'file', fs_write: 'new-file', fs_edit: 'edit', fs_delete: 'trash',
  fs_move: 'arrow-swap', fs_list: 'folder', fs_glob: 'search', project_tree: 'list-tree',
  grep: 'search', sh_exec: 'terminal', sh_background: 'pulse', build_run: 'tools',
  test_run: 'beaker', task_list: 'checklist', memory_write: 'book',
  web_fetch: 'globe', pkg_install: 'package',
};

/* ================================================================ pieces */

function topbar(opts) {
  const back = opts.back
    ? `<button class="ib" data-a="${opts.back}" aria-label="Назад">${ic('arrow-left')}</button>`
    : '<span style="width:4px"></span>';
  // The subtitle is the agent's mode, and it is never cut: the project's
  // name above it gives way instead (see .topbar-title).
  const sub = opts.sub
    ? `<button class="topbar-sub t-label-small" data-a="${opts.subAction || ''}">`
      + `<span>${esc(opts.sub)}</span>${opts.subAction ? ic('chevron-down', T.size.iconTiny) : ''}</button>`
    : '';
  return `<div class="topbar">${back}<div class="topbar-title">`
    + `<div class="t-headline ellip">${esc(opts.title || '')}</div>${sub}</div>`
    + (opts.actions || '') + '</div>';
}

/*
 * A row is a div, not a button: a row may carry its own trailing control
 * (a kebab, a bin), and a button inside a button is not valid HTML -- the
 * parser closes the outer one, which drops the control onto its own line.
 */
function rowHtml(opts) {
  const lead = opts.lead || '';
  const trail = opts.trail === undefined
    ? ic('chevron-right', T.size.iconSmall)
    : opts.trail;
  // A row with [checked] is a checkbox to assistive technology, not a button.
  const role = opts.checked === undefined ? 'button' : `checkbox" aria-checked="${opts.checked}`;
  return `<div class="row press${opts.compact ? ' compact' : ''}${opts.cls ? ' ' + opts.cls : ''}" role="${role}" tabindex="0"`
    + `${opts.current ? ' aria-current="true"' : ''}${opts.off ? ' aria-disabled="true"' : ''}`
    + ` data-a="${opts.a || ''}"${opts.d ? ` data-d="${esc(opts.d)}"` : ''}>`
    + lead
    + `<span class="row-text"><span class="row-title t-label ellip">${opts.titleHtml || esc(opts.title)}</span>`
    // [wrap]: a sentence that says what a choice does is read whole, not cut.
    + (opts.sub ? `<span class="row-sub t-body-small${opts.wrap ? '' : ' ellip'}">${esc(opts.sub)}</span>` : '')
    + '</span>'
    + (trail ? `<span class="row-trail">${trail}</span>` : '')
    + '</div>';
}

/** A control inside a row: a span, for the same reason. */
function rowButton(action, data, icon, label) {
  return `<span class="ib sm" role="button" tabindex="0" data-a="${action}" data-d="${esc(data)}" aria-label="${esc(label)}">`
    + `${ic(icon, T.size.iconSmall)}</span>`;
}

function section(label, link) {
  return `<div class="section"><span class="t-overline">${esc(label)}</span>`
    + (link ? `<button class="link t-label-small" data-a="${link.a}">${esc(link.text)}</button>` : '')
    + '</div>';
}

/* ============================================================= main menu */

function homeScreen() {
  const list = S.projects.slice(0, 4);
  const recent = list.length
    ? `<div class="card">${list.map((p) => rowHtml({
        lead: avatar(p.name),
        title: p.name,
        sub: `${ago(p.min)} · ${p.files} ${plural(p.files, 'файл', 'файла', 'файлов')}`,
        a: 'open-project',
        d: p.name,
      })).join('')}</div>`
    : `<div class="card"><div class="empty t-body-small">Проектов пока нет. Создайте новый или склонируйте репозиторий.</div></div>`;

  const notice = S.first
    ? `<div class="card notice"><span class="tile warn">${ic('key')}</span>`
      + '<div class="grow"><div class="t-label">Подключите модель</div>'
      + '<div class="t-body-small muted" style="margin-top:4px">Агенту нужен доступ к LLM: Anthropic, OpenAI, OpenRouter, Kiro через шлюз или свой URL.</div>'
      + `<button class="btn primary small t-label-small" data-a="go-settings">${ic('key', T.size.iconSmall)}Добавить ключ</button></div></div>`
    : '';

  return `<div class="screen" data-screen="home">`
    + topbar({ actions: `<button class="ib" data-a="go-settings" aria-label="Настройки">${ic('settings-gear')}</button>` })
    + `<div class="scroll" data-keep="home"><div class="home-body">`
    + '<div class="hero">'
    + `<div class="logo-tile">${DESIGN.logo}</div>`
    + '<h1 class="t-title">ApkCLI</h1>'
    + '<div class="sub t-body">Агент для кода прямо на телефоне</div>'
    + '</div>'
    + notice
    + section('Начать')
    + '<div class="card">'
    + rowHtml({ lead: `<span class="tile accent">${ic('folder-opened')}</span>`, title: 'Открыть проект', sub: `${S.projects.length} ${plural(S.projects.length, 'проект', 'проекта', 'проектов')} на устройстве`, a: 'go-open' })
    + rowHtml({ lead: `<span class="tile">${ic('new-folder')}</span>`, title: 'Создать новый', sub: 'Пустая папка в ~/projects', a: 'sheet-create' })
    + rowHtml({ lead: `<span class="tile">${ic('repo-clone')}</span>`, title: 'Клонировать репозиторий', sub: 'Публичный репозиторий с GitHub', a: 'sheet-clone' })
    + '</div>'
    + section('Недавние', S.projects.length > 4 ? { text: 'Все', a: 'go-open' } : null)
    + recent
    + '</div></div></div>';
}

/* =========================================================== open project */

function openScreen() {
  const q = S.search.trim().toLowerCase();
  const list = S.projects.filter((p) => !q || p.name.toLowerCase().includes(q));
  const rows = list.length
    ? list.map((p) => rowHtml({
        lead: avatar(p.name),
        title: p.name,
        sub: `${ago(p.min)} · ${p.files} ${plural(p.files, 'файл', 'файла', 'файлов')} · ${p.note}`,
        a: 'open-project',
        d: p.name,
        trail: rowButton('menu-project', p.name, 'kebab-vertical', 'Действия'),
      })).join('')
    : `<div class="empty t-body-small">Ничего не нашлось по «${esc(S.search)}».</div>`;

  return '<div class="screen" data-screen="open">'
    + topbar({ back: 'go-home', title: 'Открыть проект' })
    + '<div class="scroll" data-keep="open"><div class="home-body">'
    + `<div class="search t-body">${ic('search', T.size.iconSmall)}`
    + `<input id="searchInput" placeholder="Поиск по проектам" value="${esc(S.search)}" data-i="search">`
    + (S.search ? `<button class="ib xs" data-a="clear-search" aria-label="Очистить">${ic('close', T.size.iconSmall)}</button>` : '')
    + '</div>'
    + section('На устройстве')
    + `<div class="card">${rows}</div>`
    + section('Добавить')
    + '<div class="card">'
    + rowHtml({ lead: `<span class="tile">${ic('root-folder')}</span>`, title: 'Папка на устройстве…', sub: 'Скопируется в ~/projects', a: 'import-folder' })
    + rowHtml({ lead: `<span class="tile">${ic('github-inverted')}</span>`, title: 'Открыть с GitHub…', sub: 'owner/repo или ссылка', a: 'sheet-clone' })
    + '</div>'
    + '</div></div></div>';
}

/* ============================================================== settings */

function settingsScreen() {
  const endpoints = S.endpoints.map((e) => {
    const preset = PRESETS.find((p) => p.id === e.preset);
    const models = `${e.models.length} ${plural(e.models.length, 'модель', 'модели', 'моделей')}`;
    return rowHtml({
      lead: `<span class="tile${e.active ? ' accent' : ''}">${ic(preset ? preset.icon : 'server')}</span>`,
      title: e.name,
      titleHtml: `${esc(e.name)}${e.active ? ' <span class="badge t-label-tiny">Активен</span>' : ''}`,
      sub: e.loading ? 'Загрузка моделей…' : e.key ? `${e.url} · ${models}` : `${e.url} · нужен ключ`,
      a: 'edit-endpoint',
      d: e.name,
      trail: e.loading
        ? '<span class="spin"></span>'
        : e.key
          ? ic('chevron-right', T.size.iconSmall)
          : `${ic('warning', T.size.iconSmall, 'warn')}${ic('chevron-right', T.size.iconSmall)}`,
    });
  }).join('');

  const seg = (name, items, current) => `<div class="seg">${items.map((i) =>
    `<button class="t-label-small${i.id === current ? ' on' : ''}" data-a="set-${name}" data-d="${i.id}">${esc(i.name)}</button>`).join('')}</div>`;

  return '<div class="screen" data-screen="settings">'
    + topbar({ back: 'back', title: 'Настройки' })
    + '<div class="scroll" data-keep="settings"><div class="settings-body">'
    + section('Подключения')
    + `<div class="card">${endpoints}`
    + rowHtml({ lead: `<span class="tile">${ic('add')}</span>`, title: 'Добавить подключение', sub: 'Готовая схема или свой URL', a: 'sheet-presets' })
    + '</div>'
    + section('Агент')
    + '<div class="card">'
    + rowHtml({ lead: `<span class="tile accent">${ic('agent')}</span>`, title: agentOf(S.agent).name, sub: agentOf(S.agent).desc, a: 'sheet-agent', wrap: true })
    + rowHtml({ lead: `<span class="tile">${ic('thinking')}</span>`, title: 'Рассуждение', sub: reasoningOf(S.reasoning).name, a: 'sheet-reasoning' })
    + rowHtml({ lead: `<span class="tile">${ic('chip')}</span>`, title: 'Модель', sub: shortModel(S.model), a: 'sheet-model' })
    + '</div>'
    // What From Rules lets through. Editable in any mode: they apply once
    // From Rules is the one chosen, and the note says so until then.
    + '<div id="rules"></div>'
    + section('Правила', S.agent === 'rules' ? null : { text: 'Включить From Rules', a: 'use-rules' })
    + `<div class="card">${RULES.map((r) => rowHtml({
        lead: `<span class="checkbox${S.rules[r.id] ? ' on' : ''}">${S.rules[r.id] ? ic('check', T.size.iconTiny) : ''}</span>`,
        title: r.title,
        sub: r.sub,
        a: 'toggle-rule',
        d: r.id,
        trail: '',
        cls: 'rule',
        checked: !!S.rules[r.id],
        wrap: true,
      })).join('')}</div>`
    + `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>`
    + (S.agent === 'rules' ? 'Отмеченное агент делает сам' : 'В режиме From Rules отмеченное агент делает сам')
    + ', остальное — после «Разрешить». Чтение и поиск не спрашивают никогда.</span></div>'
    + section('Интерфейс')
    + '<div class="card">'
    + '<div class="setting"><div class="t-label">Язык</div>'
    + '<div class="row-sub t-body-small">Системный — русский</div>'
    + seg('lang', [{ id: 'system', name: 'Системный' }, { id: 'ru', name: 'Русский' }, { id: 'en', name: 'English' }], S.language)
    + '</div>'
    + '<div class="setting"><div class="t-label">Разделение экрана</div>'
    + '<div class="row-sub t-body-small">«Авто» разделяет экран на планшетах, складных телефонах и в альбомной ориентации.</div>'
    + seg('split', [{ id: 'auto', name: 'Авто' }, { id: 'one', name: 'Одна панель' }, { id: 'both', name: 'Всегда две' }], S.split)
    + '</div>'
    + '</div>'
    + section('О приложении')
    + '<div class="card"><div class="about">'
    + '<div class="t-label">ApkCLI 0.1.0</div>'
    + '<div class="t-body-small" style="margin-top:4px">Иконки: Codicons © Microsoft (CC BY 4.0); иконки файлов — Seti из VS Code, © 2014 Jesse Weed (MIT).</div>'
    + '</div></div>'
    + '</div></div></div>';
}

/* ============================================================= workspace */

const TABS = [
  { id: 'agent', name: 'Агент', icon: 'comment-discussion' },
  { id: 'files', name: 'Файлы', icon: 'files' },
  { id: 'terminal', name: 'Терминал', icon: 'terminal' },
];

function wsScreen() {
  const w = ws();
  const a = agentOf(S.agent);
  // Search and history beside "+", as in Kiro's chat panel. Memory has its
  // place in the overflow menu.
  const actions = w.tab === 'agent'
    ? `<button class="ib" data-a="find-open" aria-label="Найти в чате"${w.chat.length ? '' : ' disabled'}>${ic('search')}</button>`
      + `<button class="ib" data-a="sheet-history" aria-label="История чатов">${ic('history')}</button>`
      + `<button class="ib" data-a="new-chat" aria-label="Новый чат"${w.busy ? ' disabled' : ''}>${ic('add')}</button>`
      + `<button class="ib" data-a="menu-overflow" aria-label="Ещё">${ic('kebab-vertical')}</button>`
    : `<button class="ib" data-a="menu-overflow" aria-label="Ещё">${ic('kebab-vertical')}</button>`;

  const body = w.tab === 'agent' ? agentTab(w) : w.tab === 'files' ? filesTab(w) : terminalTab(w);

  const nav = TABS.map((t) => `<button class="nav${t.id === w.tab ? ' on' : ''}" data-a="tab" data-d="${t.id}">`
    + `<span class="pill">${ic(t.icon)}${badgeFor(t.id, w)}</span>`
    + `<span class="t-nav">${t.name}</span></button>`).join('');

  return '<div class="screen" data-screen="ws">'
    + (w.tab === 'agent' && w.find
      ? findTop(w)
      : topbar({ back: 'go-home', title: S.project, sub: a.name, subAction: 'sheet-agent', actions }))
    + `<div class="tabbody">${body}</div>`
    + `<div class="bottomnav">${nav}</div></div>`;
}

function badgeFor(tab, w) {
  if (tab === 'files' && w.changes) return '<span class="badge-dot"></span>';
  if (tab === 'terminal' && w.sessions.some((s) => s.running)) return '<span class="badge-dot"></span>';
  return '';
}

/* --------------------------------------------------------------- agent tab */

const SUGGESTIONS = [
  { icon: 'list-tree', text: 'Объясни, как устроен проект' },
  { icon: 'beaker', text: 'Добавь тесты на то, что уже есть' },
  { icon: 'search', text: 'Найди, почему падает запуск' },
  { icon: 'package', text: 'Обнови зависимости и проверь' },
];

/* ----------------------------------------------------------- chat commands */

/*
 * Commands typed into the composer. "/" at the start opens a plaque above
 * it, in the changes bar's place, with the commands that match what is typed
 * so far; a tap runs one, and so does sending the words when they are one.
 * Anything else that starts with "/" — a path, "/etc/hosts" — is an ordinary
 * message, and the plaque stays away.
 *
 * One command so far. /compact [what to keep] folds the conversation into a
 * summary for the agent: what the app does by itself when the window is
 * almost full, on request, and all of it rather than all but the last turns.
 * The words after it are what the summary keeps in detail. The messages stay
 * in the chat, above a seam that marks the fold, and in the agent's search.
 */
const COMMANDS = [
  { name: 'compact', icon: 'fold', title: 'Сжать контекст', arg: '[что сохранить подробно]' },
];

/**
 * The command [draft] is or begins as: its name so far, the words after it,
 * the command when the name is whole, and the commands it can still become.
 * Null when the words are not a command.
 */
function slashOf(draft) {
  const m = /^\/([a-z]*)(\s[\s\S]*)?$/i.exec(String(draft || '').trimStart());
  if (!m) return null;
  const token = m[1].toLowerCase();
  const exact = COMMANDS.find((c) => c.name === token) || null;
  // Words after the name: that command only, if it is one.
  const matches = m[2] !== undefined ? (exact ? [exact] : []) : COMMANDS.filter((c) => c.name.startsWith(token));
  return { token, args: (m[2] || '').trim(), exact, matches };
}

/** The turns the next compaction would fold: every request and answer after the last summary. */
function unfolded(w) {
  let from = 0;
  w.chat.forEach((t, i) => { if (t.role === 'seam' && t.state === 'done') from = i + 1; });
  return w.chat.slice(from).filter((t) => t.role === 'user' || t.role === 'agent');
}

/**
 * Below this much conversation a summary would not be much shorter than what
 * it replaces: the app's MIN_MANUAL_COMPACT_TOKENS. And a summary that frees
 * less than COMPACT_GAIN is not kept: nothing is folded, and the chat says so.
 */
const COMPACT_MIN = 2000;
const COMPACT_GAIN = 500;

/** Why [cmd] cannot run now, in words for its row; null when it can. */
function commandBlocked(w, cmd) {
  if (cmd.name !== 'compact') return null;
  if (w.compacting) return 'Уже сжимается.';
  if (w.busy) return 'Можно, когда агент закончит ответ.';
  if (!w.chat.some((t) => t.role === 'user')) return 'Нечего сжимать: в чате ещё нет сообщений.';
  if (!unfolded(w).length) return 'Нечего сжимать: после сводки новых сообщений нет.';
  if (contextParts(w).dialog < COMPACT_MIN) {
    return lastSummary(w) ? 'Нечего сжимать: после сводки разговор ещё короткий.' : 'Нечего сжимать: разговор ещё короткий.';
  }
  return null;
}

/** The last summary in the chat, or null. */
function lastSummary(w) {
  for (let i = w.chat.length - 1; i >= 0; i--) if (w.chat[i].role === 'seam' && w.chat[i].state === 'done') return w.chat[i];
  return null;
}

/**
 * The context as the context sheet splits it: the conversation, which a
 * compaction folds, its summary, and what no compaction shrinks — the tool
 * descriptions, the instructions, the project's memory.
 */
function contextParts(w) {
  const seam = lastSummary(w);
  const base = seam ? seam.before : w.context;
  const fixed = { tools: Math.round(base * 0.21), instructions: Math.round(base * 0.11), memory: Math.round(base * 0.06) };
  const digest = seam ? seam.digestTokens : 0;
  const dialog = Math.max(0, w.context - fixed.tools - fixed.instructions - fixed.memory - digest);
  return Object.assign({ dialog, digest }, fixed);
}

/** The plaque over the composer while the words begin a command; '' otherwise. */
function commandsHtml(w) {
  const s = slashOf(w.draft);
  if (!s || !s.matches.length) return '';
  const rows = s.matches.map((c) => {
    const why = commandBlocked(w, c);
    const about = why || (s.exact === c && s.args
      ? `Свернуть в сводку, подробно сохранив: «${s.args}».`
      : `Свернуть разговор в сводку и освободить контекст: диалог занимает ${tokens(contextParts(w).dialog)}.`);
    return `<button class="cmd${s.exact === c ? ' on' : ''}" data-a="run-command" data-d="${c.name}"${why ? ' disabled' : ''}>`
      + `<span class="cmd-ic">${ic(c.icon, T.size.iconSmall)}</span>`
      + '<span class="cmd-main">'
      + `<span class="cmd-name"><span class="t-mono-label">/${c.name}</span><span class="cmd-arg t-caption ellip">${esc(c.arg)}</span></span>`
      + `<span class="cmd-about t-body-small">${esc(about)}</span>`
      + '</span></button>';
  }).join('');
  return `<div class="cmds"><div class="cmds-head t-overline">Команды</div>${rows}</div>`;
}

function runCommand(w, name, args) {
  if (name === 'compact') runCompact(w, args);
}

function agentTab(w) {
  const body = w.chat.length
    ? `<div class="transcript" data-keep="chat" data-follow="1">${w.chat.map(turnHtml).join('')}${liveHtml(w)}</div>`
    : '<div class="chat-empty" data-keep="empty">'
      + `<span class="mark">${ic('sparkle', 28)}</span>`
      + '<div class="t-headline">Чем займёмся?</div>'
      + '<div class="body t-body">Попросите что-то изменить или задайте вопрос о проекте. Агент читает и правит настоящие файлы.</div>'
      + `<div class="suggest">${SUGGESTIONS.map((s) => `<button class="t-body" data-a="suggest" data-d="${esc(s.text)}">${ic(s.icon, T.size.iconSmall)}<span class="grow ellip">${esc(s.text)}</span></button>`).join('')}</div>`
      + '</div>';

  // Nothing in this bar is ever cut: the count is short ("4 файла", the diff
  // icon and the +/− say what changed), and when it still does not fit, the
  // buttons move to a second line rather than eat the numbers.
  // The commands plaque takes the bar's place while the words begin with "/".
  const changes = w.changes
    ? `<div class="changes"${commandsHtml(w) ? ' hidden' : ''}>${ic('diff', T.size.iconSmall)}`
      + `<span class="changes-sum t-label-small">${w.changes.files} ${plural(w.changes.files, 'файл', 'файла', 'файлов')}`
      + (w.changes.add ? ` <span class="accent">+${w.changes.add}</span>` : '')
      + (w.changes.rem ? ` <span class="err">\u2212${w.changes.rem}</span>` : '')
      + '</span>'
      + '<span class="changes-actions">'
      + '<button class="btn ghost t-label-small" data-a="sheet-changes">Просмотр</button>'
      + '<button class="btn ghost neutral t-label-small" data-a="keep-changes">Оставить</button></span></div>'
    : '';

  // While searching, the composer gives way to the matches' bar: nothing is
  // being written, and the arrows sit where the thumb is.
  return body + (w.find ? findNav() : changes + composer(w));
}

/* ------------------------------------------------------------ chat search */

/*
 * Find in the chat: the top bar turns into the field, the composer into a bar
 * with "3 из 12" and the arrows. Everything the transcript says is searched:
 * the requests, the answers, the reasoning, the tool cards' targets and their
 * output; a collapsed card or reasoning that holds a match opens while the
 * search is on. Matches are numbered top to bottom and the search starts at
 * the newest, the end of the chat where the reader is; ↑ goes back in time.
 */

/** The search's top bar: back closes it, the field takes the query. */
function findTop(w) {
  return '<div class="topbar find-top">'
    + `<button class="ib" data-a="find-close" aria-label="Закрыть поиск">${ic('arrow-left')}</button>`
    + `<div class="find-field t-body">${ic('search', T.size.iconSmall)}`
    + `<input id="findInput" placeholder="Найти в чате" value="${esc(w.find.q)}" data-i="find" autocomplete="off" spellcheck="false" enterkeyhint="search">`
    + (w.find.q ? `<button class="ib xs" data-a="find-clear" aria-label="Очистить">${ic('close', T.size.iconSmall)}</button>` : '')
    + '</div></div>';
}

/** The matches' bar. Its count and arrows are set once the matches are marked (applyFind). */
function findNav() {
  return '<div class="find-nav">'
    + '<span class="find-count grow t-label-small" aria-live="polite"></span>'
    + `<button class="ib" data-a="find-prev" aria-label="Предыдущее совпадение" disabled>${ic('chevron-up')}</button>`
    + `<button class="ib" data-a="find-next" aria-label="Следующее совпадение" disabled>${ic('chevron-down')}</button>`
    + '</div>';
}

/** The words being searched for, lowercased; '' while the search is off or empty. */
function findQuery(w) {
  return w.find ? w.find.q.trim().toLowerCase() : '';
}

/** While searching, a collapsed part that holds a match opens, so the match can be shown. */
function findOpens(w, p) {
  const q = findQuery(w);
  if (!q) return false;
  if (p.t === 'think') return String(p.text).toLowerCase().includes(q);
  // A subagent's card opens for a match in what its window shows: the task,
  // where it was told to start, its steps' targets, what it said, its answer.
  if (p.t === 'sub') return subTexts(p).some((text) => text.toLowerCase().includes(q));
  if (p.t !== 'tool' || !p.body) return false;
  const b = p.body;
  const text = b.kind === 'diff' ? b.unified : b.kind === 'term' ? String(b.text || '').replace(/\u001b\[[0-9;]*m/g, '') : b.text;
  return String(text || '').toLowerCase().includes(q);
}

/** What is searched: the words of the transcript, not its labels and buttons. */
const FIND_IN = '.u, .md, .think-body, .tool-target, .tool-body, .task, .sub-ask, .sub-files, .sub-target, .dg-p, .dg-list';

/**
 * Mark every match of [q] under [root] and return them, each as its marks
 * in order: a match may cross the spans a highlighted line is made of, so one
 * match can be several <mark>s.
 */
function markHits(root, q) {
  const needle = q.toLowerCase();
  const hits = [];
  root.querySelectorAll(FIND_IN).forEach((block) => {
    if (block.parentElement && block.parentElement.closest(FIND_IN)) return;
    const nodes = [];
    const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) nodes.push(walker.currentNode);
    const lower = nodes.map((n) => n.nodeValue).join('').toLowerCase();
    const found = [];
    for (let at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + needle.length)) {
      found.push([at, at + needle.length]);
    }
    if (!found.length) return;
    // Where each text node sits in the block's text, then each match cut into
    // the nodes it covers -- last first, so the cuts do not move the rest.
    let base = 0;
    const spans = nodes.map((n) => { const s = [base, base + n.nodeValue.length]; base = s[1]; return s; });
    const pieces = [];
    found.forEach(([from, to], hit) => spans.forEach(([start, end], node) => {
      const a = Math.max(from, start), b = Math.min(to, end);
      if (a < b) pieces.push({ node, hit, a: a - start, b: b - start });
    }));
    pieces.sort((x, y) => x.node - y.node || y.a - x.a);
    const marks = found.map(() => []);
    for (const p of pieces) {
      const text = nodes[p.node];
      text.splitText(p.b);
      const match = text.splitText(p.a);
      const mark = document.createElement('mark');
      mark.className = 'hit';
      match.parentNode.replaceChild(mark, match);
      mark.appendChild(match);
      // A match has one piece per node, and the nodes go in order.
      marks[p.hit].push(mark);
    }
    hits.push(...marks);
  });
  return hits;
}

/** After each repaint: mark the matches, pick the current one, show it once. */
function applyFind() {
  if (S.route.name !== 'ws' || !S.project) return;
  const w = ws();
  if (!w.find || w.tab !== 'agent') return;
  const f = w.find;
  const q = f.q.trim();
  const root = document.querySelector('#viewport .transcript');
  const hits = q && root ? markHits(root, q) : [];
  f.count = hits.length;
  if (f.i === null || f.i < 0 || f.i >= hits.length) f.i = hits.length - 1;
  const current = hits[f.i];
  if (current) current.forEach((m) => m.classList.add('cur'));

  const count = document.querySelector('.find-nav .find-count');
  if (count) {
    count.textContent = !q ? 'Сообщения, ответы, рассуждения и вывод команд'
      : hits.length ? `${f.i + 1} из ${hits.length}` : 'Нет совпадений';
  }
  document.querySelectorAll('.find-nav .ib').forEach((b) => { b.disabled = hits.length < 2; });

  // Scrolled to only when the query or the position changed, so a turn
  // streaming in does not pull the reader back to it on every repaint.
  const key = q + '\u0000' + f.i;
  if (current && root && f.shown !== key) {
    f.shown = key;
    const at = current[0].getBoundingClientRect();
    const box = root.getBoundingClientRect();
    root.scrollTop += at.top - box.top - root.clientHeight / 2 + at.height / 2;
  }
}

function turnHtml(turn, i) {
  if (turn.role === 'seam') return seamHtml(turn, i);
  if (turn.role === 'user') {
    const imgs = turn.imgs && turn.imgs.length
      ? `<div class="u-imgs">${turn.imgs.map((src) => `<img src="${src}" alt="">`).join('')}</div>`
      : '';
    // Kiro's checkpoint: above each message of the user's, a way back to
    // before it — the chat, the agent's context and its file changes.
    const busy = ws().busy;
    return '<div class="turn-u">'
      + `<div class="checkpoint t-label-tiny">${ic('location', T.size.iconTiny)}<span>Чекпоинт</span><i></i>`
      + `<button class="t-label-tiny" data-a="sheet-checkpoint" data-d="${i}"${busy ? ' disabled' : ''}>Восстановить</button></div>`
      + `<div class="u t-body${turn.text ? '' : ' only-imgs'}">${imgs}${esc(turn.text)}</div>`
      + '</div>';
  }
  return `<div class="a">${turn.parts.map((p, j) => partHtml(p, `${i}.${j}`)).join('')}</div>`;
}

function partHtml(p, key) {
  const w = ws();
  const open = findOpens(w, p) || !!w.open[key] || (p.open && w.open[key] !== false);
  if (p.t === 'text') return `<div class="md">${md(p.md)}</div>`;
  if (p.t === 'think') {
    return `<div><button class="think-head t-label-small" data-a="toggle" data-d="${key}" data-open="${open ? 1 : 0}">`
      + `${ic(open ? 'chevron-down' : 'chevron-right', T.size.iconSmall)}`
      + `<span class="accent">${ic('thinking', T.size.iconSmall)}</span>`
      + `Рассуждение · ${secs(p.ms)}</button>`
      + `<div class="collapse${open ? ' open' : ''}"><div><div class="think-body t-body-small">${esc(p.text)}</div></div></div></div>`;
  }
  if (p.t === 'tasks') {
    const glyph = { done: ['pass-filled', 'g-done'], now: ['circle-large', 'g-now'], todo: ['circle-large', 'g-todo'] };
    return '<div class="tool"><div class="tasks">'
      + `<div class="tasks-head"><span class="t-label">${ic('checklist', T.size.iconSmall)} План · ${p.done}/${p.items.length}</span></div>`
      + p.items.map((it) => {
          const [g, cls] = glyph[it.state] || glyph.todo;
          return `<div class="task t-body-small${it.state === 'done' ? ' done' : ''}">`
            + `<span class="${cls}">${ic(g, T.size.iconTiny)}</span><span class="grow">${esc(it.text)}</span></div>`;
        }).join('')
      + '</div></div>';
  }
  if (p.t === 'tool') return toolHtml(p, key, open);
  if (p.t === 'sub') return subHtml(p, key, open);
  return '';
}

function toolHtml(p, key, open) {
  const title = TOOL_NAMES[p.name] || p.name;
  const icon = TOOL_ICONS[p.name] || 'tools';
  const stats = p.body && p.body.kind === 'diff' ? diffStats(p.body.unified) : (p.add !== undefined ? { add: p.add, rem: p.rem } : null);
  // While the model is still writing the call, the clock counts the writing,
  // under "пишет", so the model's time never reads as the tool's.
  const status = p.wait
    ? `<span class="tool-status t-label-tiny"><span class="wait">${ic('question', T.size.iconSmall)}</span>Спрашивает</span>`
    : p.write
      ? `<span class="tool-status t-label-tiny"><span class="run spin"></span>${p.ms >= 1000 ? `пишет · ${clock(p.ms)}` : 'пишет'}</span>`
      : p.run
      ? `<span class="tool-status t-label-tiny"><span class="run spin"></span>${secs(p.ms)}</span>`
      : p.ok
        ? `<span class="tool-status t-label-tiny"><span class="ok">${ic('pass', T.size.iconSmall)}</span>${secs(p.ms)}</span>`
        : `<span class="tool-status t-label-tiny"><span class="bad">${ic('error', T.size.iconSmall)}</span>${secs(p.ms)}</span>`;

  // A zero side is left out: "+27", not "+27 −0".
  const statHtml = stats && (stats.add || stats.rem)
    ? '<span class="stat t-mono-caption">'
      + (stats.add ? `<span class="add">+${stats.add}</span>` : '')
      + (stats.rem ? `<span class="rem">\u2212${stats.rem}</span>` : '')
      + '</span>'
    : '';

  let body = '';
  if (open && p.body) {
    if (p.body.kind === 'diff') body = `<div class="tool-body">${diffRows(p.body.unified, syntaxOf(p.body.file))}</div>`;
    else if (p.body.kind === 'term') {
      body = `<div class="tool-body"><div class="termbox t-code-small">${p.body.text ? ansi(p.body.text) : '<span class="faint">без вывода</span>'}</div></div>`;
    } else body = `<div class="tool-body"><div class="tool-text t-body-small">${esc(p.body.text)}</div></div>`;
  }

  return `<div class="tool${p.ok === false ? ' failed' : ''}${p.run || p.write ? ' live' : ''}">`
    + `<button class="tool-head" data-a="toggle" data-d="${key}" data-open="${open ? 1 : 0}">`
    + `<span class="ti">${ic(icon, T.size.iconSmall)}</span>`
    + '<span class="tool-main">'
    + `<span class="tool-title t-label-medium"><span class="ellip">${esc(title)}</span>${statHtml}</span>`
    + (p.target ? `<span class="tool-target t-mono-caption ellip">${esc(p.target)}</span>` : '')
    + '</span>'
    + status
    + (p.body ? ic(open ? 'chevron-up' : 'chevron-down', T.size.iconSmall) : '')
    + '</button>' + body + '</div>';
}

/* --------------------------------------------------------------- subagents */

/*
 * A subagent's card, as a desktop agent shows one: a window onto the
 * subagent's own run inside the chat. The head is a tool card's — "Субагент",
 * what kind (an Explorer only reads; a worker changes files under the chat's
 * mode), the task's first line, the clock, a chevron. Below it:
 *
 *  * while the main agent writes the task, the task as it comes;
 *  * while the subagent runs, its last three steps, newest at the bottom —
 *    enough to see what it is doing without opening anything;
 *  * open, the whole window: the task, every step (a tap shows a step's
 *    output), its answer; it scrolls inside itself and follows a running
 *    subagent's newest step.
 *
 * Done, it folds to its head: the main agent's next words are what the user
 * reads next. A failed or stopped one says so in its status.
 */

const SUB_KIND = { explorer: 'только чтение', worker: 'меняет файлы' };

/** How many of a running subagent's steps show under its head. */
const SUB_LIVE_STEPS = 3;

/** Everything a subagent's window shows that the search looks through. */
function subTexts(p) {
  const steps = p.steps || [];
  return [p.prompt || '', ...(p.files || []), p.answer || '',
    ...steps.map((s) => s.target || ''), ...steps.filter((s) => s.t === 'text').map((s) => s.md || '')];
}

function firstLine(text) {
  return String(text || '').split('\n').map((l) => l.trim()).find(Boolean) || '';
}

/** The head's status: the tool card's glyphs and words, plus asking, refused and stopped. */
function subStatus(p) {
  const status = (glyph, text) => `<span class="tool-status t-label-tiny">${glyph}${esc(text)}</span>`;
  if (p.wait) return status(`<span class="wait">${ic('question', T.size.iconSmall)}</span>`, 'Спрашивает');
  if (p.write) return status('<span class="run spin"></span>', p.ms >= 1000 ? `пишет · ${clock(p.ms)}` : 'пишет');
  if (p.run) return status('<span class="run spin"></span>', secs(p.ms));
  if (p.denied) return status(`<span class="wait">${ic('circle-slash', T.size.iconSmall)}</span>`, 'Отклонено');
  if (p.stopped) return status(`<span class="faint">${ic('debug-stop', T.size.iconSmall)}</span>`, 'Остановлен');
  if (p.ok) return status(`<span class="ok">${ic('pass', T.size.iconSmall)}</span>`, secs(p.ms));
  return status(`<span class="bad">${ic('error', T.size.iconSmall)}</span>`, secs(p.ms));
}

/** A step's own status at the end of its row: the time it took, or a glyph while it runs, asks or failed. */
function subStepStatus(s) {
  if (s.run) return '<span class="tool-status"><span class="run spin"></span></span>';
  if (s.stopped) return `<span class="tool-status"><span class="faint">${ic('debug-stop', T.size.iconSmall)}</span></span>`;
  if (s.wait) return `<span class="tool-status t-label-tiny"><span class="wait">${ic('question', T.size.iconSmall)}</span>ждёт</span>`;
  if (s.ok === false) return `<span class="tool-status t-label-tiny"><span class="bad">${ic('error', T.size.iconSmall)}</span>${secs(s.ms)}</span>`;
  return `<span class="tool-status t-label-tiny">${secs(s.ms)}</span>`;
}

/**
 * One step of a subagent's run: a row with the tool's icon, name and target
 * (or "Рассуждение"), and with [interactive] a button that shows its output
 * under it. What it said on the way is text, not a row.
 */
function subStepHtml(s, key, interactive) {
  const w = ws();
  if (s.t === 'text') return interactive ? `<div class="md sub-say t-body-small">${md(s.md)}</div>` : '';
  const think = s.t === 'think';
  const icon = think ? 'thinking' : (TOOL_ICONS[s.name] || 'tools');
  const name = think ? (s.run ? 'Думает' : 'Рассуждение') : (TOOL_NAMES[s.name] || s.name);
  const output = think ? s.text : s.body;
  const open = interactive && !!output && !!w.open[key];
  const row = `<span class="si${think ? ' accent' : ''}">${ic(icon, T.size.iconTiny)}</span>`
    + `<span class="sub-step-name t-label-small">${esc(name)}</span>`
    + (s.target ? `<span class="sub-target t-mono-caption ellip">${esc(s.target)}</span>` : '<span class="grow"></span>')
    + subStepStatus(s);
  if (!interactive || !output) return `<div class="sub-step">${row}</div>`;
  let body = '';
  if (open) {
    if (think) body = `<div class="sub-out"><div class="think-body t-body-small">${esc(s.text)}</div></div>`;
    else if (s.body.kind === 'diff') body = `<div class="sub-out">${diffRows(s.body.unified, syntaxOf(s.body.file))}</div>`;
    else if (s.body.kind === 'term') body = `<div class="sub-out"><div class="termbox t-code-small">${ansi(s.body.text || '')}</div></div>`;
    // What a read or a search returned is code and paths: mono, as the
    // app's output box draws it, with its indentation.
    else body = `<div class="sub-out"><div class="termbox t-code-small">${esc(s.body.text)}</div></div>`;
  }
  return `<button class="sub-step" data-a="toggle" data-d="${key}" data-open="${open ? 1 : 0}">${row}</button>${body}`;
}

/** The window, open: the task, every step, the answer. */
function subWindow(p, key) {
  const w = ws();
  const steps = p.steps || [];
  const q = findQuery(w);
  // A long task shows four lines until asked, or while a match is in the rest.
  const long = (p.prompt || '').length > 200;
  const whole = !!w.open[key + '.ask'] || (q && (p.prompt || '').toLowerCase().includes(q));
  const task = '<div class="sub-sec">'
    + '<div class="sub-label t-overline">Задание</div>'
    + `<div class="sub-ask t-body-small${long && !whole ? ' clamp' : ''}">${esc(p.prompt)}${p.write ? '<span class="cursor"></span>' : ''}</div>`
    + (long ? `<button class="link sub-more t-label-small" data-a="toggle" data-d="${key}.ask" data-open="${whole ? 1 : 0}">${whole ? 'Свернуть' : 'Показать всё'}</button>` : '')
    + (p.files && p.files.length ? `<div class="sub-files t-mono-caption">Начать с: ${p.files.map(esc).join(' · ')}</div>` : '')
    + '</div>';
  const work = steps.length || p.run
    ? '<div class="sub-sec">'
      + `<div class="sub-label t-overline">Ход работы${steps.length ? ` · ${steps.length}` : ''}</div>`
      + `<div class="sub-steps">${steps.map((s, i) => subStepHtml(s, `${key}.${i}`, true)).join('')}`
      + (p.run && !p.answering && !steps.some((s) => s.run) ? '<div class="sub-step"><span class="si"><span class="spin"></span></span><span class="sub-step-name t-label-small">Ожидание модели</span></div>' : '')
      + '</div></div>'
    : '';
  let end = '';
  if (p.answer) {
    end = '<div class="sub-sec"><div class="sub-label t-overline">Ответ</div>'
      + `<div class="md sub-answer t-body-small">${md(p.answer)}${p.answering ? '<span class="cursor"></span>' : ''}</div>`
      + (p.limit ? `<div class="sub-note warn t-caption">Дошёл до лимита в ${p.limit} шагов: ответ может быть неполным.</div>` : '')
      + '</div>';
  } else if (p.ok === false && !p.run && !p.write && !p.wait) {
    end = `<div class="sub-note ${p.stopped || p.denied ? '' : 'bad '}t-caption">${esc(
      p.denied ? 'Не запущен: поручение отклонено.' : p.stopped ? 'Остановлен до ответа.' : `Не закончил: ${p.error || 'ошибка модели'}.`)}</div>`;
  }
  return `<div class="sub-win full" data-keep="sub-${key}"${p.run || p.write ? ' data-follow="1"' : ''}>${task}${work}${end}</div>`;
}

/** Under the head while the card is folded: the task being written, or the last steps of the run. */
function subLive(p, key) {
  let inner = '';
  if (p.write || p.wait) {
    const text = p.prompt || '';
    const tail = text.length > 150 ? '…' + text.slice(-150) : text;
    inner = '<div class="sub-label t-overline">Задание</div>'
      + `<div class="sub-typing t-body-small">${esc(tail)}${p.write ? '<span class="cursor"></span>' : ''}</div>`;
  } else {
    // Three rows at most, so the strip does not jump: the last steps, and
    // what it is waiting on when no step is running.
    const all = (p.steps || []).filter((s) => s.t !== 'text');
    const idle = p.answering || !all.some((s) => s.run);
    const steps = all.slice(-(idle ? SUB_LIVE_STEPS - 1 : SUB_LIVE_STEPS));
    inner = steps.map((s) => subStepHtml(s, '', false)).join('');
    if (p.answering) {
      inner += `<div class="sub-step"><span class="si accent">${ic('comment-discussion', T.size.iconTiny)}</span>`
        + '<span class="sub-step-name t-label-small">Пишет ответ</span><span class="grow"></span>'
        + '<span class="tool-status"><span class="run spin"></span></span></div>';
    } else if (idle) {
      inner += '<div class="sub-step"><span class="si"><span class="spin"></span></span>'
        + '<span class="sub-step-name t-label-small">Ожидание модели</span></div>';
    }
  }
  return `<div class="sub-win mini" data-a="toggle" data-d="${key}" data-open="0">${inner}</div>`;
}

function subHtml(p, key, open) {
  const live = p.run || p.write || p.wait;
  const kind = SUB_KIND[p.agent] || p.agent;
  const body = open ? subWindow(p, key) : live ? subLive(p, key) : '';
  return `<div class="tool sub${p.ok === false && !p.stopped && !p.denied ? ' failed' : ''}${live ? ' live' : ''}">`
    + `<button class="tool-head" data-a="toggle" data-d="${key}" data-open="${open ? 1 : 0}">`
    + `<span class="ti">${ic('hubot', T.size.iconSmall)}</span>`
    + '<span class="tool-main">'
    + `<span class="tool-title t-label-medium"><span class="ellip">Субагент</span><span class="sub-kind t-label-tiny">${esc(kind)}</span></span>`
    + (p.prompt ? `<span class="sub-task t-caption ellip">${esc(firstLine(p.prompt))}</span>` : '')
    + '</span>'
    + subStatus(p)
    + ic(open ? 'chevron-up' : 'chevron-down', T.size.iconSmall)
    + '</button>' + body + '</div>';
}

/* -------------------------------------------------------------- compaction */

/*
 * Where the context was compacted, in the transcript: a ruled line, how much
 * of the context it freed, and the summary itself, folded, as the agent reads
 * it. From then on the agent reads the summary in place of the turns above;
 * they stay on screen and in its search (conversation_search). By /compact,
 * which folds everything, or by the app when the window is almost full, which
 * keeps the latest turns as they are.
 *
 * While it runs the line alone says so, with the clock, and the composer's
 * button stops it. A failed or stopped one changes nothing, says so, and goes
 * away with the next message: it was never part of the conversation.
 */

/** Everything the summary says, for find in the chat: what its card shows, without the code marks. */
function digestText(seam) {
  const d = seam.digest;
  if (!d) return '';
  return [d.objective, d.state, ...d.decisions, ...d.edits, ...d.commands, ...d.failed, ...d.kept, ...d.next]
    .join('\n').replace(/`/g, '');
}

function seamHtml(seam, i) {
  const w = ws();
  const key = String(i);
  const line = (glyph, title) => `<div class="seam-line t-label-small"><i></i>${glyph}<span>${esc(title)}</span><i></i></div>`;
  if (seam.state === 'run') {
    // The line alone: what is happening, and for how long.
    return '<div class="seam run" role="status">'
      + line('<span class="spin"></span>', `Сжимаю контекст · ${clock(seam.ms)}`)
      + '</div>';
  }
  if (seam.state === 'failed') {
    return '<div class="seam failed">'
      + line(`<span class="warn">${ic('warning', T.size.iconSmall)}</span>`, 'Не удалось сжать')
      + `<div class="seam-sub t-caption">${esc(seam.error)} Ничего не изменилось: агент видит весь разговор, как раньше.</div>`
      + `<button class="link seam-act t-label-small" data-a="retry-compact" data-d="${i}">${ic('refresh', T.size.iconSmall)}Повторить</button></div>`;
  }
  if (seam.state === 'stopped') {
    return '<div class="seam stopped">'
      + line(`<span class="faint">${ic('debug-stop', T.size.iconSmall)}</span>`, 'Сжатие остановлено')
      + '<div class="seam-sub t-caption">Контекст не изменился.</div></div>';
  }
  if (seam.state === 'skipped') {
    return '<div class="seam skipped">'
      + line(`<span class="faint">${ic('info', T.size.iconSmall)}</span>`, 'Сжимать нечего')
      + '<div class="seam-sub t-caption">Сводка вышла не короче разговора, так что её не сохранили. Ничего не изменилось.</div></div>';
  }
  // Open when asked, or while a search has a match in the summary.
  const q = findQuery(w);
  const open = !!w.open[key] || !!(q && digestText(seam).toLowerCase().includes(q));
  const auto = seam.by === 'auto';
  return '<div class="seam done">'
    + line(`<span class="accent">${ic('fold', T.size.iconSmall)}</span>`, auto ? 'Контекст сжат автоматически' : 'Контекст сжат')
    + `<div class="seam-sub t-caption">Освобождено: ${tokens(seam.freed)}</div>`
    + (auto ? '<div class="seam-sub t-caption">Окно почти заполнилось. Последние сообщения остались как есть.</div>' : '')
    + (seam.focus ? `<div class="seam-sub t-caption">Подробно сохранено: «${esc(seam.focus)}»</div>` : '')
    + `<button class="link seam-act t-label-small" data-a="toggle" data-d="${key}" data-open="${open ? 1 : 0}">`
    + `${open ? 'Скрыть сводку' : 'Показать сводку'}${ic(open ? 'chevron-up' : 'chevron-down', T.size.iconSmall)}</button>`
    + `<div class="collapse${open ? ' open' : ''}"><div>${digestHtml(seam)}</div></div>`
    + '</div>';
}

/** The summary as the agent reads it, in the user's words: ConversationDigest's fields, the empty ones left out. */
function digestHtml(seam) {
  const d = seam.digest;
  if (!d) return '';
  const sec = (title, body) => (body ? `<div class="dg-sec"><div class="dg-h t-overline">${esc(title)}</div>${body}</div>` : '');
  const para = (text) => (text ? `<div class="dg-p t-body-small">${inline(text)}</div>` : '');
  const list = (items) => (items && items.length ? `<ul class="dg-list t-body-small">${items.map((x) => `<li>${inline(x)}</li>`).join('')}</ul>` : '');
  return '<div class="seam-digest">'
    + `<div class="dg-top t-label-small">${ic('fold', T.size.iconSmall)}<span class="grow">Сводка для агента</span>`
    + `<span class="t-mono-caption faint">${tokens(seam.digestTokens)}</span></div>`
    + sec('Цель', para(d.objective))
    + sec('Где остановились', para(d.state))
    + sec('Подробно, по просьбе', list(d.kept))
    + sec('Решения', list(d.decisions))
    + sec('Изменения', list(d.edits))
    + sec('Команды', list(d.commands))
    + sec('Не сработало', list(d.failed))
    + sec('Дальше', list(d.next))
    + '<div class="dg-foot t-caption">Агент читает эту сводку вместо сообщений выше. Они не удалены: остаются в чате, и агент находит их поиском по истории.</div>'
    + '</div>';
}

/** The turn in flight: what it has done so far, streamed thinking, a running command, the waiting row. */
function liveHtml(w) {
  if (!w.live) return '';
  const l = w.live;
  // The turn's finished parts and a subagent still running, keyed as they
  // will be once the turn lands (finishTurn carries open ones over).
  const parts = (l.parts || []).map((p, j) => partHtml(p, `live.${j}`));
  if (l.think) {
    parts.push('<div><button class="think-head t-label-small" data-a="noop">'
      + `${ic('chevron-down', T.size.iconSmall)}<span class="accent">${ic('thinking', T.size.iconSmall)}</span>Думает · ${secs(Date.now() - l.started)}</button>`
      + `<div class="collapse open"><div><div class="think-body t-body-small" data-keep="think" data-follow="1">${esc(l.think)}<span class="cursor"></span></div></div></div></div>`);
  }
  if (l.text) parts.push(`<div class="md">${md(l.text)}</div>`);
  if (l.tool) parts.push(toolHtml(l.tool, 'live', true));
  if (l.waiting) {
    parts.push(`<div class="waiting t-label-small"><span class="spin"></span>${esc(l.waiting)}</div>`);
  }
  return `<div class="a">${parts.join('')}</div>`;
}

function composer(w) {
  const m = modelOf(S.model);
  const used = w.context;
  const limit = m ? m.context : 128000;
  const pct = Math.min(1, used / limit);
  const r = 8;
  const c = 2 * Math.PI * r;
  const ring = `<svg class="i" width="${T.size.ring}" height="${T.size.ring}" viewBox="0 0 20 20">`
    + `<circle cx="10" cy="10" r="${r}" fill="none" stroke="var(--c-surface3)" stroke-width="2.5"/>`
    + `<circle cx="10" cy="10" r="${r}" fill="none" stroke="var(--c-accent)" stroke-width="2.5" stroke-linecap="round"`
    + ` stroke-dasharray="${(c * pct).toFixed(2)} ${c.toFixed(2)}" transform="rotate(-90 10 10)"/></svg>`;

  const thumbs = w.attachments.length
    ? `<div class="attachments">${w.attachments.map((src, i) => `<span class="thumb"><img src="${src}" alt="">`
      + `<button data-a="unattach" data-d="${i}" aria-label="Убрать">${ic('close', T.size.iconTiny)}</button></span>`).join('')}</div>`
    : '';

  return '<div class="composer-wrap">'
    + commandsHtml(w)
    + '<div class="composer">'
    + thumbs
    + `<textarea id="draft" class="t-body" rows="1" placeholder="Опишите, что нужно изменить" data-i="draft">${esc(w.draft)}</textarea>`
    + '<div class="composer-row">'
    + `<button class="ib xs" data-a="attach" aria-label="Прикрепить изображение">${ic('attach', T.size.icon)}</button>`
    + `<button class="ring" data-a="sheet-context" aria-label="Контекст">${ring}</button>`
    + `<button class="pick t-label-small" data-a="sheet-model"><span>${esc(shortModel(S.model))}</span>${ic('chevron-down', T.size.iconSmall)}</button>`
    + `<button class="pick fixed t-label-small" data-a="sheet-reasoning"><span>${esc(reasoningOf(S.reasoning).name)}</span>${ic('chevron-down', T.size.iconSmall)}</button>`
    + sendHtml(w)
    + '</div></div></div>';
}

/** The send button: a stop while the agent works, the command's own glyph while the words are one. */
function sendHtml(w) {
  if (w.busy) return '<button class="send" data-a="stop" aria-label="Остановить"><span class="stop"></span></button>';
  const s = slashOf(w.draft);
  const cmd = s && s.exact;
  if (cmd) {
    return `<button class="send" data-a="send" aria-label="${esc(cmd.title)}"${commandBlocked(w, cmd) ? ' disabled' : ''}>`
      + `${ic(cmd.icon, T.size.iconSmall)}</button>`;
  }
  return `<button class="send" data-a="send" aria-label="Отправить"${w.draft.trim() || w.attachments.length ? '' : ' disabled'}>`
    + `${ic('arrow-up', T.size.iconSmall)}</button>`;
}

/* --------------------------------------------------------------- files tab */

function filesTab(w) {
  if (w.filesView === 'editor' && w.active) return editorView(w);

  const rows = [];
  const walk = (nodes, depth) => {
    for (const n of nodes) {
      const pad = 16 + depth * 16;
      if (n.dir) {
        rows.push(`<button class="tr" data-a="fold" data-d="${esc(n.name)}" style="padding-left:${pad}px">`
          + `<span class="tw">${ic(n.open ? 'chevron-down' : 'chevron-right', T.size.iconSmall)}</span>`
          + `<span style="width:6px"></span>${ic(n.open ? 'folder-opened' : 'folder', T.size.iconSmall)}`
          + `<span class="name t-body ellip" style="margin-left:8px">${esc(n.name)}</span></button>`);
        if (n.open) walk(n.children, depth + 1);
      } else {
        // The icon lines up with a folder's icon on the same level: the
        // twisty's 16 plus the 6 that separates it from the folder glyph.
        rows.push(`<button class="tr${w.selected === n.path ? ' sel' : ''}" data-a="open-file" data-d="${esc(n.path)}" style="padding-left:${pad + 22}px">`
          + seti(n.name)
          + `<span class="name t-body ellip" style="margin-left:8px">${esc(n.name)}</span>`
          + (n.deco ? `<span class="deco ${n.deco} t-mono-label">${n.deco === 'm' ? 'M' : 'A'}</span>` : '')
          + '</button>');
      }
    }
  };
  walk(w.tree, 0);

  const body = w.tree.length
    ? `<div class="tree" data-keep="tree">${rows.join('')}</div>`
    : `<div class="explorer-empty">${ic('folder', 28)}<div class="t-label">Проект пуст</div>`
      + '<div class="t-body-small">Создайте первый файл или попросите агента.</div></div>';

  return '<div class="tree-head">'
    + '<span class="t-overline">Проводник</span>'
    + `<button class="ib sm" data-a="toast" data-d="Новый файл" aria-label="Новый файл">${ic('new-file', T.size.iconSmall)}</button>`
    + `<button class="ib sm" data-a="toast" data-d="Новая папка" aria-label="Новая папка">${ic('new-folder', T.size.iconSmall)}</button>`
    + `<button class="ib sm" data-a="toast" data-d="Обновлено" aria-label="Обновить">${ic('refresh', T.size.iconSmall)}</button>`
    + `<button class="ib sm" data-a="collapse" aria-label="Свернуть все">${ic('collapse-all', T.size.iconSmall)}</button>`
    + `<button class="ib sm" data-a="toast" data-d="Открыть в проводнике" aria-label="Открыть в проводнике">${ic('link-external', T.size.iconSmall)}</button>`
    + '</div>' + body;
}

function editorView(w) {
  const name = w.active.split('/').pop();
  const lines = (FILES[w.active] || '').replace(/\n$/, '').split('\n');
  const lang = syntaxOf(name);
  const gutter = String(lines.length).length * 7.8 + 28;
  const code = highlightLines(lines, lang).map((html, i) =>
    `<div class="cl t-code${i + 1 === w.cursor ? ' cur' : ''}">`
    + `<span class="n tnum" style="width:${gutter}px">${i + 1}</span><span class="t">${html || ' '}</span></div>`).join('');

  const tabs = w.tabs.map((path) => {
    const n = path.split('/').pop();
    return `<button class="tab${path === w.active ? ' on' : ''}" data-a="activate" data-d="${esc(path)}">`
      + seti(n, T.size.iconTiny)
      + `<span class="t-body-small ellip">${esc(n)}</span>`
      + `<span class="x" data-a="close-tab" data-d="${esc(path)}">${ic('close', T.size.iconTiny)}</span></button>`;
  }).join('');

  return `<div class="tabs"><button class="tree-btn" data-a="show-tree" aria-label="Проводник">${ic('list-tree', T.size.iconSmall)}</button>${tabs}</div>`
    + `<div class="code" data-keep="code"><div class="code-rows">${code}</div></div>`
    + '<div class="statusline t-mono-caption">'
    + `<span>Стр ${w.cursor}, Кол 1</span><span>${lines.length} ${plural(lines.length, 'строка', 'строки', 'строк')}</span>`
    + `<span class="grow"></span><span>${esc(lang || 'text')}</span><span>UTF-8</span></div>`;
}

/* ------------------------------------------------------------ terminal tab */

/*
 * The project's terminals in one row: the user's shells, and the chat's
 * transcript of what the agent ran, named "Агент". The transcript is where
 * the agent's commands are shown, with their output and how they ended; it
 * takes a typed line too, run the way the agent's are. Every chip closes, the
 * last one too, and then the tab says nothing is open.
 */
function terminalTab(w) {
  const s = w.sessions.find((x) => x.id === w.session) || w.sessions[0] || null;
  const chips = w.sessions.map((x) => `<button class="session${s && x.id === s.id ? ' on' : ''}" data-a="session" data-d="${x.id}">`
    + ic('terminal', T.size.iconSmall)
    + `<span class="t-label-small ellip">${esc(shellName(x))}</span>`
    + (x.running ? '<span class="dot pulse"></span>' : '')
    + `<span class="x" data-a="close-term" data-d="${x.id}" aria-label="Закрыть ${esc(shellName(x))}">${ic('close', T.size.iconTiny)}</span>`
    + '</button>').join('')
    + `<button class="ib sm" data-a="new-term" aria-label="Новый терминал">${ic('add', T.size.iconSmall)}</button>`;

  const keys = ['Ctrl-C', 'Ctrl-D', 'Tab', '↑', '↓', 'Esc', '|', '~', '/', '-'];

  const out = s
    ? `<div class="term-out t-code-small" data-keep="term-${s.id}" data-follow="1">${ansi(s.out)}<span class="cursor"></span></div>`
    : `<div class="term-out term-none">${ic('terminal', 28)}<div class="t-body-small">Открытых терминалов нет</div>`
      + `<button class="btn secondary small t-label-small" data-a="new-term">${ic('add', T.size.iconSmall)}Новый терминал</button></div>`;

  return `<div class="sessions">${chips}</div>`
    + out
    + '<div class="term-bar">'
    + '<div class="term-input">'
    + '<span class="ps t-mono-label">$</span>'
    + '<input id="termInput" class="t-code" placeholder="Введите команду и нажмите Enter" data-i="term">'
    + `<button class="ib xs" data-a="run" aria-label="Отправить">${ic('send', T.size.iconSmall)}</button>`
    + '</div>'
    + `<div class="term-keys">${keys.map((k) =>
        `<button class="key t-mono-label" data-a="key" data-d="${esc(k)}">${esc(k)}</button>`).join('')}</div>`
    + '</div>';
}

/* ================================================================ sheets */

function sheetShell(inner, cls) {
  return `<div class="overlay${cls ? ' ' + cls : ''}"><div class="scrim" data-a="close-sheet"></div>`
    + `<div class="sheet"><div class="handle"></div>${inner}</div></div>`;
}

function sheetHead(icon, title, sub, tone) {
  // A title alone sits level with its tile, not at its top edge.
  return `<div class="sheet-head${sub ? '' : ' one'}">`
    + (icon ? `<span class="tile${tone ? ' ' + tone : ''}">${ic(icon)}</span>` : '')
    + `<div class="grow"><div class="sheet-title t-headline">${esc(title)}</div>`
    + (sub ? `<div class="sheet-sub t-body-small">${esc(sub)}</div>` : '')
    + '</div></div>';
}

function radioRow(opts) {
  return `<button class="row" data-a="${opts.a}" data-d="${esc(opts.d)}">`
    + `<span class="radio${opts.on ? ' on' : ''}"></span>`
    + `<span class="row-text"><span class="row-title t-label ellip">${esc(opts.title)}</span>`
    + (opts.sub ? `<span class="row-sub t-body-small">${esc(opts.sub)}</span>` : '')
    + '</span></button>';
}

/** `owner/repo` and an optional ref — ProjectImport.parseGitHub, same rules. */
function parseGitHub(input) {
  let text = String(input).trim().replace(/\/$/, '');
  if (!text) return null;
  let ref = null;
  text = text.replace(/^git@github\.com:/, '').replace(/^(?:https?:\/\/)?(?:www\.)?github\.com\//, '');
  if (text.includes('@') && !text.includes('://')) {
    ref = text.slice(text.indexOf('@') + 1) || null;
    text = text.slice(0, text.indexOf('@'));
  }
  const parts = text.split('/');
  if (parts.length < 2) return null;
  const owner = parts[0];
  const repo = parts[1].replace(/\.git$/, '');
  if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/.test(owner)) return null;
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(repo) || repo === '.' || repo === '..') return null;
  if (parts.length >= 4 && (parts[2] === 'tree' || parts[2] === 'commit')) ref = parts.slice(3).join('/') || null;
  if (ref && (!/^[A-Za-z0-9._/-]{1,200}$/.test(ref) || ref.includes('..'))) ref = null;
  return { owner, repo, ref };
}

function sheetHtml() {
  switch (S.sheet) {
    case 'create': return createSheet();
    case 'clone': return cloneSheet();
    case 'agent': return agentSheet();
    case 'model': return modelSheet();
    case 'reasoning': return reasoningSheet();
    case 'memory': return memorySheet();
    case 'history': return historySheet();
    case 'chat-rename': return chatRenameSheet();
    case 'chat-delete': return chatDeleteSheet();
    case 'note': return noteSheet();
    case 'presets': return presetsSheet();
    case 'approval': return approvalSheet();
    case 'checkpoint': return checkpointSheet();
    case 'context': return contextSheet();
    case 'changes': return changesSheet();
    case 'endpoint': return endpointSheet();
    case 'confirm': return confirmSheet();
    default: return '';
  }
}

function createSheet() {
  const name = S.create.input.trim();
  return sheetShell(
    sheetHead('new-folder', 'Создать проект', 'Пустая папка в ~/projects с AGENTS.md для заметок агенту.')
    + '<div class="field"><span class="field-label t-label-small">Имя</span>'
    + `<div class="input${S.create.error ? ' error' : ''} t-body">${ic('folder', T.size.iconSmall)}`
    + `<input id="createInput" placeholder="my-project" value="${esc(S.create.input)}" data-i="create" autocomplete="off"></div>`
    + `<div class="helper t-caption${S.create.error ? ' error' : ''}">${esc(S.create.error || (name ? '~/projects/' + name : 'Буквы, цифры, дефис'))}</div></div>`
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="close-sheet">Отмена</button>'
    + `<button class="btn primary t-label" data-a="do-create"${name ? '' : ' disabled'}>Создать</button></div>`
  );
}

function cloneSheet() {
  const c = S.clone;
  if (c.step > 0) {
    const done = c.step === 3;
    const failed = c.step === 4;
    return sheetShell(
      sheetHead(failed ? 'error' : done ? 'pass' : 'cloud-download',
        failed ? 'Не удалось открыть' : done ? 'Готово' : 'Клонирование',
        c.name, failed ? 'error' : done ? 'accent' : '')
      + (failed
        ? '<div class="note t-body-small">' + ic('info', T.size.iconSmall)
          + '<span>Репозиторий или ветка не найдены. Открываются только публичные репозитории — проверьте владельца, имя и ветку.</span></div>'
        : `<div class="progress${done ? ' done' : ''}"><i></i></div>`
          + `<div class="progress-line t-body-small">${done ? ic('pass', T.size.iconSmall, 'accent') : ''}`
          + `<span>${c.step === 1 ? 'Скачивание · ' + mb(c.bytes) : c.step === 2 ? 'Распаковка · ' + c.files + ' ' + plural(c.files, 'файл', 'файла', 'файлов') : 'Записано файлов: ' + c.files + ' (' + mb(c.bytes) + ')'}</span></div>`)
      + '<div class="sheet-actions">'
      + (done
        ? `<button class="btn secondary t-label" data-a="close-sheet">Позже</button><button class="btn primary t-label" data-a="open-cloned">Открыть</button>`
        : failed
          ? '<button class="btn secondary t-label" data-a="clone-again">Назад</button><button class="btn primary t-label" data-a="close-sheet">Закрыть</button>'
          : '<button class="btn secondary t-label block" data-a="close-sheet">Отмена</button>')
      + '</div>'
    );
  }

  const parsed = parseGitHub(c.input);
  return sheetShell(
    sheetHead('repo-clone', 'Клонировать репозиторий', 'owner/repo, ссылка или owner/repo@ветка.')
    + '<div class="field"><span class="field-label t-label-small">Репозиторий</span>'
    + `<div class="input${c.error ? ' error' : ''} t-body">${ic('github-inverted', T.size.iconSmall)}`
    + `<input id="cloneInput" placeholder="owner/repo или ссылка" value="${esc(c.input)}" data-i="clone" autocomplete="off" spellcheck="false"></div>`
    + `<div class="helper t-caption${c.error ? ' error' : ''}">${esc(c.error || '')}</div></div>`
    + (parsed
      ? `<div class="parsed t-label-small">${ic('repo', T.size.iconSmall)}<span class="ellip">${esc(parsed.owner + '/' + parsed.repo)}</span>`
        + (parsed.ref ? `${ic('git-branch', T.size.iconTiny)}<span class="t-mono-caption muted">${esc(parsed.ref)}</span>` : '')
        + '</div>'
      : '<div class="parsed"></div>')
    + `<div class="note t-body-small">${ic('info', T.size.iconSmall)}`
    + '<span>Скачиваются файлы ветки без истории git, поэтому коммитить и пушить отсюда нельзя. Приватные репозитории не поддерживаются.</span></div>'
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="close-sheet">Отмена</button>'
    + `<button class="btn primary t-label" data-a="do-clone"${parsed ? '' : ' disabled'}>Клонировать</button></div>`
  );
}

function agentSheet() {
  return sheetShell(
    sheetHead('agent', 'Режим агента', 'Когда он спрашивает разрешения.')
    + '<div class="sheet-body list-sheet">'
    + `<div class="card">${AGENTS.map((a) => radioRow({
        a: 'set-agent', d: a.id, on: a.id === S.agent, title: a.name, sub: a.desc,
      })).join('')}</div>`
    // The rules From Rules follows, one tap away from choosing it.
    + `<div class="card">${rowHtml({
        lead: `<span class="tile">${ic('checklist')}</span>`,
        title: 'Правила',
        sub: rulesSummary(),
        a: 'go-rules',
        wrap: true,
      })}</div>`
    + '</div>'
  );
}

function modelSheet() {
  const c = conn();
  const models = c ? c.models : [];
  const q = S.modelSearch.trim().toLowerCase();
  const list = models.filter((m) => !q || m.id.toLowerCase().includes(q) || m.name.toLowerCase().includes(q));
  const rows = list.length
    ? `<div class="card">${list.map((m) => radioRow({
        a: 'set-model', d: m.id, on: m.id === S.model, title: m.id, sub: modelLine(m),
      })).join('')}</div>`
    : `<div class="empty t-body-small">${models.length
        ? `Ничего не нашлось по «${esc(S.modelSearch)}».`
        : 'Этот сервер не отдал список моделей. Добавьте id вручную.'}</div>`;

  const others = S.endpoints.filter((e) => e !== c);
  const switcher = others.length
    ? section('Другие подключения')
      + `<div class="card">${others.map((e) => rowHtml({
          lead: `<span class="tile">${ic('plug')}</span>`,
          title: e.name,
          sub: e.key
            ? `${e.models.length} ${plural(e.models.length, 'модель', 'модели', 'моделей')}`
            : 'нужен API-ключ',
          a: 'set-conn',
          d: e.name,
        })).join('')}</div>`
    : '';

  const source = c && models.length ? CATALOGUE_SOURCE[c.catalogue] : null;

  return sheetShell(
    sheetHead('chip', 'Модель',
      `${c ? c.name : 'Нет подключения'} · ${models.length} ${plural(models.length, 'модель', 'модели', 'моделей')}`)
    + (models.length > 6
      ? `<div class="search t-body" style="margin-top:16px">${ic('search', T.size.iconSmall)}`
        + `<input id="modelSearch" placeholder="Поиск по моделям" value="${esc(S.modelSearch)}" data-i="model-search" autocomplete="off" spellcheck="false">`
        + (S.modelSearch ? `<button class="ib xs" data-a="clear-model-search" aria-label="Очистить">${ic('close', T.size.iconSmall)}</button>` : '')
        + '</div>'
      : '')
    + `<div class="sheet-body list-sheet">${rows}`
    + (source ? `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>${esc(source)}</span></div>` : '')
    + `${switcher}</div>`
  );
}

function reasoningSheet() {
  return sheetShell(
    sheetHead('thinking', 'Рассуждение', 'Сколько модель думает перед ответом.')
    + `<div class="sheet-body list-sheet"><div class="card">${REASONING.map((r) => radioRow({
        a: 'set-reasoning', d: r.id, on: r.id === S.reasoning, title: r.name, sub: r.desc,
      })).join('')}</div></div>`
  );
}

const SCOPES = [
  { id: 'project', name: 'Этот проект' },
  { id: 'global', name: 'Глобально' },
];

function scopeName(id) {
  return (SCOPES.find((s) => s.id === id) || SCOPES[0]).name;
}

function memorySheet() {
  const w = ws();
  const group = (scope) => {
    const items = w.memory.filter((f) => f.scope === scope);
    if (!items.length) return '';
    return section(scopeName(scope)) + `<div class="card flat">${items.map((f) => rowHtml({
      title: f.key,
      sub: f.value,
      a: 'edit-note',
      d: f.key,
      trail: rowButton('forget', f.key, 'trash', `Удалить ${f.key}`),
    })).join('')}</div>`;
  };
  return sheetShell(
    sheetHead('book', 'Память агента', 'Всё это агент видит в каждом запросе. Заметки он сохраняет сам или когда вы просите «запомни…».')
    + '<div class="sheet-body list-sheet">'
    + group('project')
    + group('global')
    + (w.memory.length ? '' : '<div class="empty t-body-small">Пока пусто.</div>')
    + `<div class="note t-body-small">${ic('markdown', T.size.iconSmall)}<span>Длинные заметки — в AGENTS.md проекта, он тоже читается в каждом запросе.</span></div>`
    + '</div>'
    + `<div class="sheet-actions"><button class="btn secondary t-label block" data-a="add-note">${ic('add', T.size.iconSmall)}Добавить заметку</button></div>`
  );
}

/*
 * The project's chats, newest first, under Сегодня, Вчера and Ранее. A row
 * is the chat's title (its first request) over when it was last used and
 * its last request, when that is another one. The open chat is a step
 * lighter with its icon in accent. Tapping a row opens that chat; its ⋮ has
 * rename and delete. While a turn runs, the turn's chat cannot be left: the
 * other rows wait, and the open one pulses.
 */
function historySheet() {
  const w = ws();
  const all = byRecent(w.chats);
  const q = S.historySearch.trim().toLowerCase();
  const found = all.filter((c) => !q
    || (c.title || 'Новый чат').toLowerCase().includes(q)
    || lastRequest(turnsOf(w, c)).toLowerCase().includes(q));
  const groups = [['Сегодня', 0], ['Вчера', 1], ['Ранее', 2]].map(([label, n]) => {
    const items = found.filter((c) => Math.min(2, daysBack(c.at)) === n);
    return items.length ? section(label) + `<div class="card flat chats">${items.map((c) => chatRow(w, c)).join('')}</div>` : '';
  }).join('');
  const count = all.length;
  const fresh = count === 1 && !w.chat.length;
  return sheetShell(
    sheetHead('history', 'История чатов', `${S.project} · ${count} ${plural(count, 'чат', 'чата', 'чатов')}`)
    + (count > 5
      ? `<div class="search t-body" style="margin-top:16px">${ic('search', T.size.iconSmall)}`
        + `<input id="historySearch" placeholder="Найти чат" value="${esc(S.historySearch)}" data-i="history-search" autocomplete="off" spellcheck="false">`
        + (S.historySearch ? `<button class="ib xs" data-a="clear-history-search" aria-label="Очистить">${ic('close', T.size.iconSmall)}</button>` : '')
        + '</div>'
      : '')
    + '<div class="sheet-body list-sheet history" data-keep="history">'
    + (w.busy
      ? `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>Агент работает в открытом чате. Другой откроется, когда ход закончится.</span></div>`
      : '')
    + groups
    + (q && !found.length ? `<div class="empty t-body-small">Ничего не нашлось по «${esc(S.historySearch.trim())}».</div>` : '')
    + (fresh
      ? `<div class="note t-body-small">${ic('history', T.size.iconSmall)}<span>Здесь будут прошлые чаты проекта: новый чат не стирает предыдущий.</span></div>`
      : '')
    + '</div>'
    + `<div class="sheet-actions"><button class="btn secondary t-label block" data-a="new-chat"${w.busy || !w.chat.length ? ' disabled' : ''}>`
    + `${ic('add', T.size.iconSmall)}Новый чат</button></div>`
  );
}

function chatRow(w, c) {
  const open = c.id === w.chatId;
  const locked = w.busy && !open;
  const turns = turnsOf(w, c);
  const title = c.title || 'Новый чат';
  const last = lastRequest(turns);
  const when = turns.length ? chatWhen(c.at) : 'пустой';
  // The working chat says so instead of offering a menu; an empty one has
  // nothing to rename or delete.
  const trail = open && w.busy
    ? '<span class="dot pulse" role="img" aria-label="работает"></span>'
    : turns.length ? rowButton('menu-chat', c.id, 'kebab-vertical', `Действия: ${title}`) : '';
  // While its turn runs, the open chat says so where its time would be.
  const state = open && w.busy ? 'работает' : when;
  return rowHtml({
    lead: `<span class="chat-ic">${ic('comment-discussion', T.size.icon)}</span>`,
    title,
    sub: last && last !== c.title ? `${state} · ${last}` : state,
    a: locked ? '' : 'open-chat',
    d: c.id,
    cls: (open ? 'cur' : '') + (locked ? ' off' : ''),
    current: open,
    off: locked,
    trail,
  });
}

function chatRenameSheet() {
  const c = ws().chats.find((x) => x.id === S.chatTarget);
  if (!c) return '';
  const value = S.rename !== null ? S.rename : c.title;
  return sheetShell(
    sheetHead('edit', 'Переименовать чат')
    + '<div class="field" style="margin-top:16px"><span class="field-label t-label-small">Название</span>'
    + '<div class="input t-body">'
    + `<input id="chatRename" value="${esc(value)}" data-i="chat-rename" maxlength="80" autocomplete="off"></div>`
    + '<div class="helper t-caption">Сначала чат называется первой строкой первого сообщения.</div></div>'
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="sheet-history">Отмена</button>'
    + `<button class="btn primary t-label" data-a="do-rename-chat"${value.trim() ? '' : ' disabled'}>Сохранить</button></div>`
  );
}

function chatDeleteSheet() {
  const w = ws();
  const c = w.chats.find((x) => x.id === S.chatTarget);
  if (!c) return '';
  const open = c.id === w.chatId;
  const after = !open ? '' : w.chats.length > 1 ? ' Откроется предыдущий чат.' : ' Откроется новый пустой чат.';
  return sheetShell(
    sheetHead('trash', 'Удалить чат?',
      `«${c.title || 'Новый чат'}» удалится вместе с сообщениями и чекпоинтами. Файлы проекта останутся как есть.${after}`,
      'error')
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="sheet-history">Отмена</button>'
    + '<button class="btn danger t-label" data-a="do-delete-chat">Удалить</button></div>'
  );
}

const NOTE_LIMIT = 280;

/*
 * One note.
 *
 * The key is a field of its own, and required, because it is not an internal
 * handle: AgentLoop puts every note into the prompt as "- key: value", so the
 * model reads the key as the note's topic. Deriving it from the text would
 * say the same words twice. It is also the address memory_write replaces and
 * memory_forget drops.
 *
 * Claude Code asks for no key, but its memory is a line of markdown in a file
 * that nothing ever updates in place. Ours is updated in place, so it needs a
 * name.
 */
function noteSheet() {
  const f = S.note;
  if (!f) return '';
  const over = f.value.trim().length > NOTE_LIMIT;
  return sheetShell(
    // Scope is said once, under the switch, and not repeated here where it
    // would go stale the moment the switch moves.
    sheetHead('book', f.editing ? 'Изменить заметку' : 'Новая заметка',
      'Короткий факт, который агент должен держать в голове.')
    + '<div class="sheet-body">'
    + '<div class="field" style="margin-top:16px"><span class="field-label t-label-small">Где помнить</span>'
    + `<div class="seg">${SCOPES.map((s) =>
        `<button class="t-label-small${s.id === f.scope ? ' on' : ''}" data-a="set-note-scope" data-d="${s.id}">${esc(s.name)}</button>`).join('')}</div>`
    + `<div class="helper t-caption">${f.scope === 'project'
        ? `Только в проекте ${esc(S.project)}.`
        : 'Во всех проектах, включая новые.'}</div></div>`
    + '<div class="field"><span class="field-label t-label-small">Ключ</span>'
    + `<div class="input${f.error === 'key' ? ' error' : ''} t-body">`
    + `<input id="noteKey" value="${esc(f.key)}" placeholder="запуск" data-i="note-key" autocomplete="off" spellcheck="false"></div>`
    + `<div class="helper t-caption${f.error === 'key' ? ' error' : ''}">${esc(f.error === 'key'
        ? (f.message || '')
        : 'Агент видит его как тему заметки и по нему же её обновляет.')}</div></div>`
    + '<div class="field"><span class="field-label t-label-small">Заметка</span>'
    + `<div class="area${f.error === 'value' ? ' error' : ''} t-body">`
    + `<textarea id="noteValue" rows="3" placeholder="Одно-два предложения" data-i="note-value">${esc(f.value)}</textarea></div>`
    + `<div class="helper t-caption${over || f.error === 'value' ? ' error' : ''}">`
    + `${f.error === 'value' ? esc(f.message || '') : `${f.value.trim().length} / ${NOTE_LIMIT}`}</div></div>`
    + '</div>'
    + '<div class="sheet-actions">'
    + (f.editing
      ? `<button class="btn danger t-label" data-a="forget" data-d="${esc(f.editing)}">Удалить</button>`
      : '<button class="btn secondary t-label" data-a="sheet-memory">Отмена</button>')
    + '<button class="btn primary t-label" data-a="save-note">Сохранить</button></div>'
  );
}

function approvalSheet() {
  const rules = S.agent === 'rules';
  if (S.ask && S.ask.kind === 'sub') return subApprovalSheet(S.ask, rules);
  return sheetShell(
    sheetHead('terminal', 'Запустить команду?',
      rules ? 'Команды не отмечены в правилах, поэтому агент спрашивает.' : 'Режим Confirm спрашивает перед каждым изменением.',
      'warn')
    + '<div class="sheet-body">'
    + `<div class="termbox t-code-small" style="margin-top:16px;max-height:120px">${ansi('\u001b[32m~/projects/' + esc(S.project) + '\u001b[0m $ \u001b[1mpython -m pytest -q\u001b[0m')}</div>`
    + `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>Команда выполнится в папке проекта, с доступом к вашим файлам.</span></div>`
    + '</div>'
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="deny">Запретить</button>'
    + '<button class="btn primary t-label" data-a="allow">Разрешить</button></div>'
    + '<div style="margin-top:12px;text-align:center">'
    + (rules
      ? '<button class="link t-label-small" data-a="allow-rule" data-d="commands">Разрешить и не спрашивать о командах</button>'
      : '<button class="link t-label-small" data-a="switch-bypass">Не спрашивать: перейти на Bypass Mode</button>')
    + '</div>'
  );
}

/**
 * Handing a task to a subagent, where the mode asks about it (Confirm, or
 * From Rules without subagents ticked): the task itself, in words, is what
 * is being approved.
 */
function subApprovalSheet(ask, rules) {
  const explorer = ask.agent === 'explorer';
  return sheetShell(
    sheetHead('hubot', 'Поручить субагенту?',
      rules ? 'Субагенты не отмечены в правилах, поэтому агент спрашивает.' : 'Режим Confirm спрашивает перед каждым изменением.',
      'warn')
    + '<div class="sheet-body">'
    + `<div class="sub-label t-overline" style="margin-top:16px">Задание · ${esc(SUB_KIND[ask.agent] || ask.agent)}</div>`
    + `<div class="ask-prompt t-body-small">${esc(ask.prompt)}</div>`
    + `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>${explorer
      ? 'Субагент только читает и ищет: ничего не изменит и ни о чём не спросит. Его ответ получит агент.'
      : 'Субагент работает в режиме чата: о своих изменениях он спросит так же.'}</span></div>`
    + '</div>'
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="deny">Запретить</button>'
    + '<button class="btn primary t-label" data-a="allow">Разрешить</button></div>'
    + '<div style="margin-top:12px;text-align:center">'
    + (rules
      ? '<button class="link t-label-small" data-a="allow-rule" data-d="subagents">Разрешить и не спрашивать о субагентах</button>'
      : '<button class="link t-label-small" data-a="switch-bypass">Не спрашивать: перейти на Bypass Mode</button>')
    + '</div>'
  );
}

function contextSheet() {
  const w = ws();
  const m = modelOf(S.model);
  const limit = m ? m.context : 128000;
  // The conversation is what a compaction folds; once one has, its summary is a part of its own.
  const p = contextParts(w);
  const items = [
    { name: 'Диалог', value: p.dialog },
    ...(p.digest ? [{ name: 'Сводка', value: p.digest }] : []),
    { name: 'Описания инструментов', value: p.tools },
    { name: 'Инструкции', value: p.instructions },
    { name: 'Память проекта', value: p.memory },
  ];
  const blocked = commandBlocked(w, COMMANDS[0]);
  return sheetShell(
    sheetHead('pulse', 'Использование контекста', `${shortModel(S.model)} · окно ${tokens(limit)}`)
    + '<div class="sheet-body">'
    + `<div class="ctx-bar" style="margin-top:18px"><i style="width:${Math.min(100, (w.context / limit) * 100).toFixed(1)}%"></i></div>`
    // The app compacts by itself at 90% of the window (ContextSettings.hardThreshold).
    + `<div class="progress-line t-body-small"><span>Занято ${tokens(w.context)} · ${tokens(Math.max(0, Math.round(limit * 0.9) - w.context))} до сжатия</span></div>`
    + `<div class="card facts" style="margin-top:16px">${items.map((i) => rowHtml({
        compact: true, title: i.name, a: '', trail: `<span class="t-mono-caption muted">${tokens(i.value)}</span>`,
      })).join('')}</div>`
    + `<div class="note t-body-small">${ic('fold', T.size.iconSmall)}<span>Сжатие сворачивает диалог в сводку для агента: само, когда окно почти заполнится, или по команде /compact в чате.</span></div>`
    + '</div>'
    + '<div class="sheet-actions">'
    + `<button class="btn secondary t-label" data-a="run-command" data-d="compact"${blocked ? ' disabled' : ''}>${ic('fold', T.size.iconSmall)}Сжать сейчас</button></div>`
  );
}

/*
 * Back to a checkpoint. The counts are what goes: the user's messages and
 * the agent's replies from that one on, and the files changed since. What
 * commands changed in the terminal was never snapshotted and stays.
 * ChatViewModel.askRestoreCheckpoint and restoreCheckpoint in the app.
 */
function checkpointSheet() {
  const w = ws();
  const at = S.checkpoint || 0;
  // A summary is not a message; one written since goes, and its turns are in the context again.
  const later = w.chat.slice(at);
  const messages = later.filter((t) => t.role !== 'seam').length;
  const unfold = later.some((t) => t.role === 'seam' && t.state === 'done');
  const firstUser = w.chat.findIndex((t) => t.role === 'user');
  const files = w.changes && at <= firstUser ? w.changes.files : 0;
  const facts = [['Удалится сообщений', messages], ['Восстановится файлов', files]];
  if (unfold) facts.push(['Сжатие контекста', 'отменится']);
  return sheetShell(
    sheetHead('location', 'Вернуться к этому чекпоинту?',
      'Чат, контекст агента и изменённые им файлы станут такими, какими были до этого сообщения. Его текст вернётся в поле ввода.',
      'accent')
    + '<div class="sheet-body">'
    + `<div class="card facts" style="margin-top:16px">${facts.map(([k, v]) => rowHtml({
        compact: true, title: k, a: '', trail: `<span class="t-mono-caption">${v}</span>`,
      })).join('')}</div>`
    + `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>Изменения, сделанные командами в терминале, не откатываются.</span></div>`
    + '</div>'
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="close-sheet">Отмена</button>'
    + '<button class="btn primary t-label" data-a="do-restore">Восстановить</button></div>'
  );
}

function changesSheet() {
  const files = [
    { path: 'bot/handlers.py', kind: 'создан', add: 15, rem: 0 },
    { path: 'bot/main.py', kind: 'изменён', add: 19, rem: 1 },
    { path: 'requirements.txt', kind: 'изменён', add: 2, rem: 0 },
    { path: 'tests/test_handlers.py', kind: 'создан', add: 28, rem: 0 },
  ];
  return sheetShell(
    sheetHead('diff', 'Изменения агента', 'Каждый файл можно вернуть к состоянию до первого изменения.')
    + `<div class="sheet-body list-sheet"><div class="card">${files.map((f) => rowHtml({
        lead: seti(f.path.split('/').pop(), T.size.icon),
        title: f.path,
        sub: `${f.kind} · +${f.add}` + (f.rem ? ` \u2212${f.rem}` : ''),
        a: 'open-change',
        d: f.path,
        trail: rowButton('toast', `Откатить ${f.path}`, 'discard', `Откатить ${f.path}`),
      })).join('')}</div></div>`
    + '<div class="sheet-actions">'
    + '<button class="btn danger t-label" data-a="toast" data-d="Откатить все">Откатить все</button>'
    + '<button class="btn primary t-label" data-a="keep-changes">Принять все</button></div>'
  );
}

function presetsSheet() {
  return sheetShell(
    sheetHead('plug', 'Новое подключение', 'Остаётся вставить ключ: адрес, формат запросов и заголовки у схемы уже прописаны.')
    + `<div class="sheet-body list-sheet"><div class="card">${PRESETS.map((p) => {
        const added = S.endpoints.some((e) => e.preset === p.id);
        const count = (CATALOGUE[p.id] || []).length;
        return rowHtml({
          lead: `<span class="tile${p.id === 'custom' ? '' : ' accent'}">${ic(p.icon)}</span>`,
          title: p.name,
          titleHtml: `${esc(p.name)}${added ? ' <span class="badge neutral t-label-tiny">уже есть</span>' : ''}`,
          sub: count ? `${p.sub} · ${count} ${plural(count, 'модель', 'модели', 'моделей')}` : p.sub,
          a: 'pick-preset',
          d: p.id,
        });
      }).join('')}</div></div>`
  );
}

/*
 * The form.
 *
 * For a scheme it is one field, because everything else is already known:
 * paste the credential, save, and the model list is fetched. A custom URL is
 * the only case that has to ask for the address and the wire format too.
 */
function endpointSheet() {
  const f = S.form;
  if (!f) return '';
  const proto = PROTOCOLS.find((p) => p.id === f.protocol) || PROTOCOLS[0];
  const keyField = '<div class="field">'
    + `<span class="field-label t-label-small">${esc(f.field || 'API-ключ')}</span>`
    + `<div class="input${f.error ? ' error' : ''} t-body">${ic('key', T.size.iconSmall)}`
    + `<input id="formKey" type="${f.reveal ? 'text' : 'password'}" value="${esc(f.keyInput || '')}"`
    + ` placeholder="${esc(f.hint || '')}" data-i="form-key" spellcheck="false" autocomplete="off">`
    + `<button class="ib xs" data-a="reveal" aria-label="Показать">${ic(f.reveal ? 'eye-closed' : 'eye', T.size.iconSmall)}</button></div>`
    + `<div class="helper t-caption${f.error ? ' error' : ''}">${esc(f.error
        || (f.where ? `Где взять: ${f.where}` : 'Если сервер его требует'))}</div></div>`;

  const body = f.custom
    ? '<div class="field"><span class="field-label t-label-small">Имя</span>'
      + `<div class="input t-body"><input id="formName" value="${esc(f.name)}" placeholder="Как назвать" data-i="form-name" autocomplete="off"></div></div>`
      + '<div class="field"><span class="field-label t-label-small">Базовый URL</span>'
      + `<div class="input t-body"><input id="formUrl" value="${esc(f.url)}" placeholder="https://host/v1" data-i="form-url" spellcheck="false" autocomplete="off"></div>`
      + '<div class="helper t-caption">До /v1. Маршрут приложение добавит само.</div></div>'
      + '<div class="field"><span class="field-label t-label-small">Формат запросов</span>'
      + `<div class="seg">${PROTOCOLS.filter((p) => p.id !== 'kiro').map((p) =>
          `<button class="t-label-small${p.id === f.protocol ? ' on' : ''}" data-a="set-protocol" data-d="${p.id}">${esc(p.name)}</button>`).join('')}</div>`
      + `<div class="helper t-caption">${esc(proto.effect)}</div></div>`
      + keyField
      + '<div class="field"><span class="field-label t-label-small">Заголовки</span>'
      + `<div class="input t-body"><input id="formHeaders" value="${esc(f.headers || '')}" placeholder="X-Title: ApkCLI" data-i="form-headers" spellcheck="false" autocomplete="off"></div>`
      + '<div class="helper t-caption">По одному в строке, если прокси требует маршрутизацию или атрибуцию.</div></div>'
    : keyField
      + `<div class="facts card" style="margin-top:20px">${[
          ['Адрес', `${f.url}${proto.path}`],
          ['Формат', proto.name],
          ['Проверка', f.check],
        ].map(([k, v]) => rowHtml({
          compact: true,
          title: k,
          titleHtml: `<span class="fact-key t-label-small">${esc(k)}</span>`,
          trail: `<span class="t-mono-caption muted ellip" style="max-width:220px">${esc(v)}</span>`,
        })).join('')}</div>`;

  return sheetShell(
    sheetHead(f.icon || 'plug', f.existing ? f.name : (f.name || 'Кастомный эндпоинт'),
      f.custom ? 'Любой сервер, совместимый с OpenAI или Anthropic' : f.sub)
    + `<div class="sheet-body">${body}`
    + (f.note ? `<div class="note t-body-small">${ic('info', T.size.iconSmall)}<span>${esc(f.note)}</span></div>` : '')
    + '</div>'
    + '<div class="sheet-actions">'
    + (f.existing
      ? '<button class="btn danger t-label" data-a="sheet-confirm">Удалить</button>'
      : '<button class="btn secondary t-label" data-a="sheet-presets">Назад</button>')
    + '<button class="btn primary t-label" data-a="save-endpoint">Сохранить</button></div>'
  );
}

function confirmSheet() {
  return sheetShell(
    sheetHead('warning', `Удалить ${S.route.endpoint || 'подключение'}?`, 'Сохранённый API-ключ и список моделей тоже удалятся. Диалоги останутся.', 'error')
    + '<div class="sheet-actions">'
    + '<button class="btn secondary t-label" data-a="sheet-endpoint">Отмена</button>'
    + '<button class="btn danger t-label" data-a="do-delete-endpoint">Удалить</button></div>'
  );
}

/* ========================================================= menus и toast */

/*
 * Only what has nowhere else to live. Switching agents is the top bar's
 * subtitle and going back to the project list is the back arrow, so putting
 * either here would be a second door to the same room.
 */
const OVERFLOW = [
  { icon: 'book', text: 'Память агента', a: 'sheet-memory' },
  { icon: 'settings-gear', text: 'Настройки', a: 'go-settings' },
];

/*
 * The tree's three menus. A file has nothing to create in, so creating is on
 * a folder's (inside it) and on the empty space below the rows (the
 * project's root), which also has what a folder's has bar renaming and
 * deleting the project from inside it.
 */
const FILE_MENU = [
  { icon: 'edit', text: 'Переименовать', a: 'toast', d: 'Переименовать' },
  { sep: true },
  { icon: 'link-external', text: 'Открыть в другом приложении', a: 'toast', d: 'Открыть в другом приложении' },
  { sep: true },
  { icon: 'trash', text: 'Удалить', a: 'toast', d: 'Удалить', danger: true },
];
const FOLDER_MENU = [
  { icon: 'new-file', text: 'Новый файл', a: 'toast', d: 'Новый файл' },
  { icon: 'new-folder', text: 'Новая папка', a: 'toast', d: 'Новая папка' },
  { icon: 'edit', text: 'Переименовать', a: 'toast', d: 'Переименовать' },
  { sep: true },
  { icon: 'link-external', text: 'Открыть в проводнике', a: 'toast', d: 'Открыть в проводнике' },
  { sep: true },
  { icon: 'trash', text: 'Удалить', a: 'toast', d: 'Удалить', danger: true },
];
const TREE_MENU = [
  { icon: 'new-file', text: 'Новый файл', a: 'toast', d: 'Новый файл' },
  { icon: 'new-folder', text: 'Новая папка', a: 'toast', d: 'Новая папка' },
  { sep: true },
  { icon: 'link-external', text: 'Открыть в проводнике', a: 'toast', d: 'Открыть в проводнике' },
];
/** A chat's ⋮ in the history sheet. */
const CHAT_MENU = [
  { icon: 'edit', text: 'Переименовать', a: 'sheet-chat-rename' },
  { sep: true },
  { icon: 'trash', text: 'Удалить', a: 'sheet-chat-delete', danger: true },
];
const MENUS = { overflow: OVERFLOW, file: FILE_MENU, folder: FOLDER_MENU, tree: TREE_MENU, chat: CHAT_MENU };

const PROJECT_MENU = [
  { icon: 'go-to-file', text: 'Открыть', a: 'open-menu-project' },
  { icon: 'edit', text: 'Переименовать', a: 'toast', d: 'Переименовать' },
  { icon: 'archive', text: 'Выгрузить в архив', a: 'toast', d: 'Выгрузить в архив' },
  { sep: true },
  { icon: 'trash', text: 'Удалить проект', a: 'toast', d: 'Удалить проект', danger: true },
];

function menuHtml() {
  if (!S.menu) return '';
  const items = MENUS[S.menu.kind] || PROJECT_MENU;
  const body = items.map((i) => i.sep
    ? '<div class="menu-sep"></div>'
    : `<button class="menu-item t-body${i.danger ? ' danger' : ''}" data-a="${i.a}" data-d="${esc(i.d || S.menu.data || '')}">`
      + `${ic(i.icon, T.size.iconSmall)}<span class="grow ellip">${esc(i.text)}</span></button>`).join('');
  const style = `top:${S.menu.y}px;${S.menu.right ? `right:${S.menu.right}px` : `left:${S.menu.x}px`}`;
  return `<div class="menu-layer" data-a="close-menu"><div class="menu" style="${style}">${body}</div></div>`;
}

function toastHtml() {
  if (!S.toast) return '';
  return `<div class="toast" style="bottom:${S.toast.bottom || 88}px">`
    + `${ic(S.toast.icon || 'info', T.size.iconSmall)}<span class="grow t-body-small">${esc(S.toast.text)}</span></div>`;
}

function toast(text, icon) {
  S.toast = { text, icon, bottom: S.route.name === 'ws' ? 88 : 32 };
  render();
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { S.toast = null; render(); }, 2200);
}

/* ================================================================ render */

function screenHtml() {
  switch (S.route.name) {
    case 'open': return openScreen();
    case 'settings': return settingsScreen();
    case 'ws': return wsScreen();
    default: return homeScreen();
  }
}

function routeKey() {
  return S.route.name + '/' + (S.project || '') + '/' + (S.route.name === 'ws' ? ws().tab : '');
}

let lastKey = null;

/*
 * The address the app itself last wrote.
 *
 * A repaint can arrive late -- a sheet sliding out, a model list landing, a
 * toast expiring -- and it rewrites the address from the state it belonged
 * to. The browser then delivers a hashchange for that write, which would
 * navigate back to where we no longer are. Remembering what we wrote lets the
 * listener tell our own writes from a real one.
 */
let wroteHash = null;

function render(dir) {
  saveScroll();
  const v = $('viewport');
  v.innerHTML = screenHtml() + sheetHtml() + menuHtml() + toastHtml();
  const screen = v.querySelector('.screen');
  const key = routeKey();
  if (screen && key !== lastKey && lastKey !== null) screen.classList.add(dir === 'back' ? 'in-back' : 'in');
  lastKey = key;
  restoreScroll();
  if (S.reveal) revealOnce(v);
  applyFind();
  autosize();
  refocus();
  const overlay = v.querySelector('.overlay');
  if (overlay) requestAnimationFrame(() => overlay.classList.add('open'));
  wroteHash = hashOf();
  history.replaceState(null, '', wroteHash);
  renderPanel();
}

/** Once, after the repaint a link asked for (demoSub): the card it is about, near the top of the transcript. */
function revealOnce(v) {
  const target = v.querySelector(S.reveal);
  S.reveal = null;
  S.revealed = true;
  const el = target && (target.closest('.tool') || target);
  const box = el && el.closest('.transcript');
  if (!box) return;
  box.scrollTop += el.getBoundingClientRect().top - box.getBoundingClientRect().top - 8;
}

function saveScroll() {
  document.querySelectorAll('[data-keep]').forEach((el) => {
    S.scroll[el.dataset.keep] = {
      top: el.scrollTop,
      follow: el.scrollHeight - el.scrollTop - el.clientHeight < 24,
    };
  });
}

/*
 * Inner scroll survives a repaint, and a view that was at its bottom stays
 * there — the transcript and the terminal follow new output, but stop
 * following the moment you scroll up. ChatScreen does this with a
 * NestedScrollConnection; here the flag is recomputed before each render.
 */
function restoreScroll() {
  document.querySelectorAll('[data-keep]').forEach((el) => {
    const state = S.scroll[el.dataset.keep];
    if (!state) { if (el.dataset.follow) el.scrollTop = el.scrollHeight; return; }
    el.scrollTop = el.dataset.follow && state.follow ? el.scrollHeight : state.top;
  });
}

function autosize() {
  const t = $('draft');
  if (!t) return;
  t.style.height = 'auto';
  t.style.height = Math.min(132, t.scrollHeight) + 'px';
}

function refocus() {
  if (!S.focus) return;
  const el = $(S.focus.id);
  if (!el) return;
  el.focus({ preventScroll: true });
  if (S.focus.caret != null && el.setSelectionRange) {
    try { el.setSelectionRange(S.focus.caret, S.focus.caret); } catch (e) { /* type=password etc. */ }
  }
}

/* =============================================================== routing */

/**
 * ?sub=<state>: a subagent's card held in one state, for links and shots.
 * `open` opens the sample's finished card and one of its steps; `writing`,
 * `live` and `live-open` stop the simulated turn's card at that point,
 * without running anything. Any other link takes a held turn back out of
 * the chat: it was never the chat's.
 */
function demoSub(w, state) {
  if (w.demo) {
    w.chat.splice(w.demo.from);
    if (w.busy && w.live && w.live.demo) { w.busy = false; w.live = null; }
    Object.keys(w.open).filter((k) => k.startsWith('live.')).forEach((k) => delete w.open[k]);
    w.demo = null;
  }
  if (!state) return;
  if (state === 'open') {
    for (let i = 0; i < w.chat.length; i++) {
      const j = (w.chat[i].parts || []).findIndex((p) => p.t === 'sub');
      if (j < 0) continue;
      w.open[`${i}.${j}`] = true;
      const grep = w.chat[i].parts[j].steps.findIndex((s) => s.name === 'grep');
      if (grep >= 0) w.open[`${i}.${j}.${grep}`] = true;
      S.reveal = '.sub-win.full';
      break;
    }
    return;
  }
  // A turn of the user's own is running: it is not ours to replace.
  if (w.busy) return;
  const sub = { t: 'sub', agent: SIM_SUB.agent, prompt: SIM_SUB.prompt, files: SIM_SUB.files, steps: [], ms: 0 };
  if (state === 'writing') {
    sub.write = true;
    sub.prompt = SIM_SUB.prompt.slice(0, 96);
    sub.ms = 2400;
  } else {
    sub.run = true;
    sub.ms = 3100;
    sub.steps = SIM_SUB.steps.slice(0, 3).map((s, k) => ({ ...s, ok: k < 2, run: k === 2 }));
  }
  w.demo = { from: w.chat.length };
  w.chat.push({ role: 'user', text: 'Проверь тесты', imgs: [] });
  w.live = { demo: true, started: Date.now() - 4000, parts: [{ t: 'think', ms: 2600, text: LIVE_THINK }, { t: 'text', md: LIVE_TEXT }, sub] };
  w.busy = true;
  if (state === 'live-open') w.open['live.2'] = true;
}

/**
 * ?compact=<state>: the /compact command held in one state, for links and
 * shots. `menu` and `typed` put "/", or the command with what to keep, into
 * the composer; `run`, `done`, `open` and `failed` end the chat with a
 * compaction at that point, and `auto` puts one the app made by itself above
 * the latest exchange. Null takes a held one back out.
 */
function demoCompact(w, state) {
  const held = w.compactDemo;
  if (held) {
    if (held.seam) {
      const at = w.chat.indexOf(held.seam);
      if (at >= 0) {
        w.chat.splice(at, 1);
        delete w.open[String(at)];
      }
      w.context = held.context;
      if (w.compacting === held.seam) { w.compacting = null; w.busy = false; }
    }
    if (held.draft !== undefined && w.draft === held.draft) w.draft = '';
    w.compactDemo = null;
  }
  if (!state) return;
  if (state === 'menu' || state === 'typed') {
    w.draft = state === 'menu' ? '/' : '/compact ' + DEMO_FOCUS;
    w.compactDemo = { draft: w.draft };
    S.focus = { id: 'draft', caret: w.draft.length };
    return;
  }
  // Something of the user's own is running, or there is nothing to fold.
  if (w.busy || !unfolded(w).length) return;
  const auto = state === 'auto';
  // The app keeps the latest turns as they are: the seam goes above the last request.
  const lastAsk = auto ? w.chat.map((t) => t.role).lastIndexOf('user') : -1;
  const kept = auto && lastAsk > 0 ? w.chat.splice(lastAsk) : [];
  const seam = newSeam(w, auto ? 'auto' : 'command', state === 'done' || state === 'open' || state === 'run' ? DEMO_FOCUS : '');
  w.compactDemo = { seam, context: w.context };
  w.chat.push(seam);
  if (state === 'run') {
    seam.started = Date.now() - 2600;
    seam.ms = 2600;
    w.compacting = seam;
    w.busy = true;
  } else if (state === 'failed') {
    seam.state = 'failed';
    seam.error = 'Модель не вернула сводку.';
  } else {
    foldInto(w, seam, auto ? 0.35 : 0);
    if (state === 'open') w.open[String(w.chat.length - 1)] = true;
    // Its seam at the top of the screen: the summary below it, or the turns it kept.
    if (state === 'open' || auto) S.reveal = '.seam.done';
  }
  w.chat.push(...kept);
}

function hashOf() {
  const flags = [S.first ? 'first' : null, S.shot ? 'shot' : null].filter(Boolean);
  const q = flags.length ? '?' + flags.join('&') : '';
  const sheet = S.sheet ? '/' + S.sheet : '';
  if (S.route.name === 'ws') return `#/ws/${encodeURIComponent(S.project)}/${ws().tab === 'files' && ws().filesView === 'editor' ? 'editor' : ws().tab}${sheet}${q}`;
  if (S.route.name === 'open') return `#/open${sheet}${q}`;
  if (S.route.name === 'settings') return `#/settings${sheet}${q}`;
  return `#/home${sheet}${q}`;
}

function applyHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query] = raw.split('?');
  const flags = (query || '').split('&');
  S.first = flags.includes('first');
  S.shot = S.shot || flags.includes('shot');
  if (flags.includes('w360')) { S.device = 360; fitMode(); }
  const parts = path.split('/').filter(Boolean);
  S.sheet = null;
  if (parts[0] === 'ws' && parts[1]) {
    const name = decodeURIComponent(parts[1]);
    if (!S.projects.some((p) => p.name === name)) S.projects.unshift({ name, min: 0, files: 1, note: 'новый' });
    S.project = name;
    S.route = { name: 'ws' };
    const w = ws();
    const tab = parts[2] || 'agent';
    w.tab = tab === 'editor' ? 'files' : (TABS.some((t) => t.id === tab) ? tab : 'agent');
    w.filesView = tab === 'editor' ? 'editor' : 'tree';
    if (parts[3]) S.sheet = parts[3];
    // Bypass Mode never asks, so the deep link puts the agent in one that does.
    if (S.sheet === 'approval' && !asks('commands')) S.agent = 'confirm';
    if (S.sheet === 'history') S.historySearch = '';
    // ?find=<words> opens the chat with its search on, for links and shots.
    const find = flags.find((f) => f.startsWith('find='));
    w.find = find !== undefined && w.tab === 'agent' && w.chat.length
      ? { q: decodeURIComponent(find.slice(5)), i: null, count: 0, shown: null }
      : null;
    // A link to renaming or deleting a chat is about the newest one not open.
    if ((S.sheet === 'chat-rename' || S.sheet === 'chat-delete') && !w.chats.some((c) => c.id === S.chatTarget)) {
      S.chatTarget = (byRecent(w.chats).find((c) => c.id !== w.chatId) || w.chats[0]).id;
    }
    if (S.sheet === 'chat-rename') S.rename = null;
    // The approval link with ?sub asks about handing a task to a subagent;
    // ?sub=<state> holds a subagent's card in one state (demoSub).
    const subFlag = flags.find((f) => f === 'sub' || f.startsWith('sub='));
    S.ask = S.sheet === 'approval' && subFlag === 'sub' ? { kind: 'sub', agent: SIM_SUB.agent, prompt: SIM_SUB.prompt } : null;
    if (S.ask && !asks('subagents')) S.agent = 'confirm';
    // Both held states out first, then the one asked for: each puts its turns at the chat's end.
    const compactFlag = flags.find((f) => f.startsWith('compact='));
    demoCompact(w, null);
    demoSub(w, subFlag && subFlag.startsWith('sub=') ? subFlag.slice(4) : null);
    demoCompact(w, compactFlag ? compactFlag.slice(8) : null);
  } else if (parts[0] === 'open') {
    S.route = { name: 'open' };
    if (parts[1]) S.sheet = parts[1];
  } else if (parts[0] === 'settings') {
    S.route = { name: 'settings' };
    if (parts[1]) S.sheet = parts[1];
  } else {
    S.route = { name: 'home' };
    if (parts[1]) S.sheet = parts[1];
  }
  // A link to a form opens it empty, not half-filled from last time.
  if (S.sheet === 'clone') S.clone = { input: '', error: null, step: 0, bytes: 0, files: 0 };
  if (S.sheet === 'create') S.create = { input: '', error: null };
  if (S.sheet === 'model') S.modelSearch = '';
  if (S.sheet === 'note' && !S.note) {
    S.note = { scope: 'project', key: '', value: '', editing: null, error: null, message: null };
  }
  if (S.sheet !== 'note') S.note = null;
  if (S.sheet === 'presets') S.form = null;
  if (S.sheet === 'endpoint' && !S.form) {
    const e = S.endpoints[0];
    if (e) {
      S.route.endpoint = e.name;
      S.form = formFor(PRESETS.find((x) => x.id === e.preset), e);
    } else {
      S.form = formFor(PRESETS.find((x) => x.id === 'custom'));
    }
  }
  if (S.first) {
    S.endpoints = S.endpoints.map((e) => Object.assign({}, e, { key: false, models: [], active: false }));
    S.projects = [];
  }
  render('none');
}

function go(name, dir) {
  S.sheet = null;
  S.menu = null;
  S.route = { name };
  render(dir);
}

function closeSheet() {
  const overlay = document.querySelector('.overlay');
  // An approval closed without an answer is a refusal, as the app's sheet
  // treats a dismissal; the turn waiting on it would wait forever otherwise.
  const approval = S.sheet === 'approval';
  const refused = approval && S.pending;
  S.sheet = null;
  if (approval) S.ask = null;
  if (refused) { const f = S.pending; S.pending = null; f(false); }
  if (!overlay) { render(); return; }
  overlay.classList.remove('open');
  wroteHash = hashOf();
  history.replaceState(null, '', wroteHash);
  // Repaint once the sheet has slid out -- unless something else already
  // repainted, in which case this overlay is gone and so is the state it
  // belonged to.
  setTimeout(() => { if (overlay.isConnected) render(); }, T.motion.sheetOut);
}

function openSheet(name, form) {
  S.menu = null;
  S.sheet = name;
  if (form !== undefined) S.form = form;
  render();
}

/** The form for a scheme, pre-filled from an existing connection if there is one. */
function formFor(preset, endpoint) {
  const p = preset || PRESETS[PRESETS.length - 1];
  return {
    preset: p.id,
    icon: p.icon,
    sub: p.sub,
    custom: !!p.custom,
    field: p.field,
    hint: p.hint,
    prefix: p.prefix,
    where: p.where,
    check: p.check,
    catalogue: p.catalogue,
    note: p.note,
    name: endpoint ? endpoint.name : (p.custom ? '' : p.name),
    url: endpoint ? endpoint.url : p.url,
    protocol: endpoint ? endpoint.protocol : p.protocol,
    headers: endpoint ? (endpoint.headers || '') : (p.headers || ''),
    // A saved credential is never read back out of the keystore, so the field
    // starts empty and an empty field means "keep the one you have".
    keyInput: '',
    saved: endpoint ? endpoint.key : false,
    reveal: false,
    error: null,
    existing: !!endpoint,
  };
}

/**
 * Save, then ask the endpoint what it serves.
 *
 * The paste is checked against the scheme's credential shape first: catching
 * a truncated key here costs nothing, while finding out from a 401 costs a
 * round trip and reads like the service is broken.
 */
function saveEndpoint() {
  const f = S.form;
  if (!f) { closeSheet(); return; }
  const key = (f.keyInput || '').trim();
  if (!key && !f.saved && !f.custom) {
    f.error = `Вставьте ${String(f.field).toLowerCase()}`;
    render('none');
    return;
  }
  if (key && f.prefix && !key.startsWith(f.prefix)) {
    f.error = `Не похоже на ${f.field.toLowerCase()}: ожидается ${f.prefix}…`;
    render('none');
    return;
  }

  let endpoint = f.existing ? S.endpoints.find((e) => e.name === S.route.endpoint) : null;
  if (endpoint) {
    Object.assign(endpoint, {
      name: f.name || endpoint.name, url: f.url, protocol: f.protocol,
      headers: f.headers, key: endpoint.key || !!key,
    });
  } else {
    endpoint = {
      preset: f.preset, name: f.name || 'Без имени', url: f.url || 'https://host/v1',
      protocol: f.protocol, headers: f.headers, catalogue: f.catalogue,
      key: true, models: [], active: !S.endpoints.some((e) => e.active),
    };
    S.endpoints.push(endpoint);
  }
  S.first = false;
  closeSheet();
  if (key || !endpoint.models.length) loadModels(endpoint);
}

/**
 * Save a note.
 *
 * A key is unique within its scope, not across both: the same key may mean
 * one thing for this project and another everywhere else, and changing the
 * scope of an existing note has to move it rather than duplicate it.
 */
function saveNote() {
  const w = ws();
  const f = S.note;
  const key = f.key.trim();
  const value = f.value.trim();
  if (!key) {
    Object.assign(f, { error: 'key', message: 'Введите ключ' });
    render('none');
    return;
  }
  const taken = w.memory.some((n) => n.key === key && n.scope === f.scope && n.key !== f.editing);
  if (taken) {
    Object.assign(f, { error: 'key', message: 'Заметка с таким ключом здесь уже есть' });
    render('none');
    return;
  }
  if (!value) {
    Object.assign(f, { error: 'value', message: 'Введите заметку' });
    render('none');
    return;
  }
  if (value.length > NOTE_LIMIT) {
    Object.assign(f, { error: 'value', message: `Не больше ${NOTE_LIMIT} символов — длинное пишите в AGENTS.md` });
    render('none');
    return;
  }
  if (f.editing) w.memory = w.memory.filter((n) => n.key !== f.editing);
  w.memory.push({ scope: f.scope, key, value });
  S.note = null;
  openSheet('memory');
}

/** GET /models, as the app does once a credential is in place. */
async function loadModels(endpoint) {
  endpoint.loading = true;
  render('none');
  await sleep(900);
  endpoint.loading = false;
  endpoint.models = CATALOGUE[endpoint.preset] || [];
  if (!endpoint.models.length) {
    toast(`${endpoint.name}: список моделей не отдан, добавьте id вручную`, 'warning');
  } else {
    if (endpoint.active) S.model = endpoint.models[0].id;
    toast(`${endpoint.name}: ${endpoint.models.length} ${plural(endpoint.models.length, 'модель', 'модели', 'моделей')}`, 'pass');
  }
}

/* ================================================================ events */

function act(a, d, ev) {
  const w = S.project ? ws() : null;
  switch (a) {
    case 'noop': case 'close-menu': S.menu = null; render(); break;
    case 'go-home': S.project = null; go('home', 'back'); break;
    case 'go-open': go('open'); break;
    case 'go-settings': go('settings'); break;
    case 'back': go(S.project ? 'ws' : 'home', 'back'); break;
    case 'close-sheet': closeSheet(); break;

    case 'sheet-create': S.create = { input: '', error: null }; openSheet('create'); break;
    case 'sheet-clone': S.clone = { input: '', error: null, step: 0, bytes: 0, files: 0 }; openSheet('clone'); break;
    case 'sheet-agent': openSheet('agent'); break;
    case 'sheet-model': openSheet('model'); break;
    case 'sheet-reasoning': openSheet('reasoning'); break;
    case 'sheet-memory': openSheet('memory'); break;
    case 'sheet-history': S.historySearch = ''; openSheet('history'); break;
    case 'open-chat': if (w) { openChat(w, d); closeSheet(); } break;
    // From the top bar or the history sheet: no toast, the empty chat is the answer.
    case 'new-chat':
      if (!w || w.busy) break;
      newChat(w);
      if (S.sheet) closeSheet(); else render('none');
      break;
    case 'menu-chat': S.menu = { kind: 'chat', y: Math.min(ev.clientY - 20, 700), right: 20, data: d }; render('none'); break;
    case 'sheet-chat-rename': S.chatTarget = d; S.rename = null; openSheet('chat-rename'); break;
    case 'sheet-chat-delete': S.chatTarget = d; openSheet('chat-delete'); break;
    case 'do-rename-chat': {
      const c = w && w.chats.find((x) => x.id === S.chatTarget);
      const name = (S.rename !== null ? S.rename : (c ? c.title : '')).trim();
      if (c && name) c.title = name;
      S.rename = null;
      openSheet('history');
      break;
    }
    case 'do-delete-chat':
      if (w) deleteChat(w, S.chatTarget);
      S.chatTarget = null;
      openSheet('history');
      toast('Чат удалён', 'trash');
      break;
    case 'clear-history-search': S.historySearch = ''; S.focus = null; render('none'); break;

    case 'find-open':
      if (!w || !w.chat.length) break;
      w.find = { q: '', i: null, count: 0, shown: null };
      S.focus = { id: 'findInput', caret: 0 };
      render('none');
      break;
    case 'find-close': if (w) w.find = null; S.focus = null; render('none'); break;
    case 'find-clear':
      if (w && w.find) { w.find.q = ''; w.find.i = null; }
      S.focus = { id: 'findInput', caret: 0 };
      render('none');
      break;
    // ↑ is back in time, towards the chat's start; both wrap around.
    case 'find-prev': case 'find-next':
      if (w && w.find && w.find.count > 1) {
        const step = a === 'find-prev' ? -1 : 1;
        w.find.i = (w.find.i + step + w.find.count) % w.find.count;
        render('none');
      }
      break;
    case 'sheet-context': openSheet('context'); break;
    case 'sheet-changes': openSheet('changes'); break;
    case 'sheet-checkpoint': S.checkpoint = Number(d); openSheet('checkpoint'); break;
    case 'do-restore': {
      const turn = w && w.chat[S.checkpoint];
      if (turn) {
        const firstUser = w.chat.findIndex((t) => t.role === 'user');
        if (S.checkpoint <= firstUser) w.changes = null;
        // A summary written since goes with the rest: the turns it folded count again.
        const undone = w.chat.slice(S.checkpoint).find((t) => t.role === 'seam' && t.state === 'done');
        if (undone) w.context = undone.before;
        w.chat = w.chat.slice(0, S.checkpoint);
        w.draft = turn.text || '';
        w.attachments = (turn.imgs || []).slice();
      }
      closeSheet();
      toast('Восстановлено до чекпоинта', 'location');
      break;
    }
    case 'sheet-presets': S.form = null; openSheet('presets'); break;
    case 'sheet-endpoint': openSheet('endpoint'); break;
    case 'sheet-confirm': openSheet('confirm'); break;

    case 'pick-preset': openSheet('endpoint', formFor(PRESETS.find((x) => x.id === d))); break;
    case 'edit-endpoint': {
      const e = S.endpoints.find((x) => x.name === d);
      if (!e) break;
      S.route.endpoint = e.name;
      openSheet('endpoint', formFor(PRESETS.find((x) => x.id === e.preset), e));
      break;
    }
    case 'reveal': S.form.reveal = !S.form.reveal; render('none'); break;
    case 'set-protocol': S.form.protocol = d; render('none'); break;

    case 'open-project': case 'open-menu-project':
      S.project = d; S.menu = null; S.sheet = null; S.route = { name: 'ws' }; render(); break;
    case 'clear-search': S.search = ''; S.focus = null; render(); break;
    case 'clear-model-search': S.modelSearch = ''; S.focus = null; render('none'); break;
    case 'import-folder':
      toast('Системный выбор папки: в прототипе не открывается', 'root-folder'); break;

    case 'do-create': {
      const name = S.create.input.trim();
      if (S.projects.some((p) => p.name === name)) { S.create.error = `${name} уже существует`; render(); break; }
      if (!/^[A-Za-z0-9._-]+$/.test(name)) { S.create.error = 'Недопустимое имя'; render(); break; }
      S.projects.unshift({ name, min: 0, files: 1, note: 'новый' });
      S.project = name; S.sheet = null; S.route = { name: 'ws' }; render();
      break;
    }
    case 'do-clone': runClone(); break;
    case 'clone-again': S.clone.step = 0; render(); break;
    case 'open-cloned':
      S.project = S.clone.name.split('@')[0].split('/').pop();
      S.sheet = null; S.route = { name: 'ws' }; render();
      break;

    case 'set-agent': S.agent = d; closeSheet(); break;
    case 'toggle-rule': S.rules[d] = !S.rules[d]; render('none'); break;
    case 'use-rules': S.agent = 'rules'; render('none'); toast('Режим From Rules', 'checklist'); break;
    // From the mode sheet: Settings, at the rules.
    case 'go-rules':
      go('settings');
      requestAnimationFrame(() => {
        const box = document.querySelector('[data-keep="settings"]');
        const at = document.getElementById('rules');
        if (box && at) box.scrollTop = at.offsetTop - box.offsetTop - 8;
      });
      break;
    case 'set-model': S.model = d; closeSheet(); break;
    case 'set-conn': {
      const e = S.endpoints.find((x) => x.name === d);
      if (!e) break;
      if (!e.key) { toast(`${e.name}: сначала нужен API-ключ`, 'warning'); break; }
      S.endpoints.forEach((x) => { x.active = x === e; });
      if (e.models.length) S.model = e.models[0].id;
      S.modelSearch = '';
      render('none');
      break;
    }
    case 'set-reasoning': S.reasoning = d; closeSheet(); break;
    case 'set-lang': S.language = d; render(); break;
    case 'set-split': S.split = d; render(); break;
    case 'save-endpoint': saveEndpoint(); break;
    case 'do-delete-endpoint':
      S.endpoints = S.endpoints.filter((x) => x.name !== S.route.endpoint);
      closeSheet(); toast('Подключение удалено', 'trash');
      break;

    case 'tab':
      // The search belongs to the chat on screen: leaving the tab ends it.
      if (d !== 'agent') w.find = null;
      w.tab = d; w.filesView = d === 'files' ? w.filesView : w.filesView; render(); break;
    // What was drawn says whether it is open: a part may be open by default
    // (a failed call) or for a search, and flipping the stored flag alone
    // left such a part open on the first tap.
    case 'toggle': {
      const el = ev && ev.target && ev.target.closest('[data-a]');
      const shown = el && el.dataset.open !== undefined ? el.dataset.open === '1' : !!w.open[d];
      w.open[d] = !shown;
      render('none');
      break;
    }
    case 'suggest': w.draft = d; S.focus = null; render('none'); break;
    case 'send': startTurn(); break;
    case 'stop': stopTurn(); break;
    // A command from its plaque, with the words typed after it; or from the context sheet.
    case 'run-command': {
      S.sheet = null;
      const s = slashOf(w.draft);
      runCommand(w, d, s && s.exact && s.exact.name === d ? s.args : '');
      break;
    }
    case 'retry-compact': {
      const seam = w.chat[Number(d)];
      if (!seam || seam.role !== 'seam') break;
      w.chat.splice(Number(d), 1);
      runCompact(w, seam.focus);
      break;
    }
    case 'attach': $('filePick').click(); break;
    case 'unattach': w.attachments.splice(+d, 1); render('none'); break;
    // No toast: the bar disappearing is the confirmation.
    case 'keep-changes': w.changes = null; closeSheet(); break;
    case 'open-change':
      w.tab = 'files'; w.filesView = 'editor'; w.active = d; w.selected = d; w.cursor = 1;
      if (!w.tabs.includes(d)) w.tabs.push(d);
      closeSheet();
      break;

    case 'fold': {
      const find = (nodes) => nodes.forEach((n) => { if (n.name === d) n.open = !n.open; else if (n.children) find(n.children); });
      find(w.tree); render('none'); break;
    }
    case 'collapse':
      (function close(nodes) { nodes.forEach((n) => { if (n.children) { n.open = false; close(n.children); } }); })(w.tree);
      render('none'); break;
    case 'open-file':
      w.selected = d; w.active = d; w.cursor = 1; w.filesView = 'editor';
      if (!w.tabs.includes(d)) w.tabs.push(d);
      render(); break;
    case 'activate': w.active = d; w.cursor = 1; render('none'); break;
    case 'close-tab':
      w.tabs = w.tabs.filter((p) => p !== d);
      if (w.active === d) w.active = w.tabs[w.tabs.length - 1] || null;
      if (!w.active) w.filesView = 'tree';
      render('none'); break;
    case 'show-tree': w.filesView = 'tree'; render('back'); break;

    case 'session': w.session = d; render('none'); break;
    case 'new-term': w.session = newShell(w).id; render('none'); break;
    case 'run': runShell(); break;
    case 'key': {
      const s = w.sessions.find((x) => x.id === w.session);
      // Ctrl-C interrupts whatever that session is running, whoever started
      // it. If it was the agent's command, the agent is told it was cut off.
      if (d === 'Ctrl-C') {
        if (s && s.running) {
          s.running = false;
          s.out += '\u001b[31m^C\u001b[0m\n\u001b[2m[exit 130]\u001b[0m\n'
            + `\u001b[32m~/projects/${S.project}\u001b[0m $ `;
          if (w.busy) stopTurn(); else render('none');
        }
        break;
      }
      if (d === 'Ctrl-D' || d === 'Esc' || d === '↑' || d === '↓' || d === 'Tab') break;
      const el = $('termInput');
      if (el) { el.value += d; el.focus(); }
      break;
    }
    case 'close-term':
      // Any of them, the last one too: the tab then says nothing is open.
      w.sessions = w.sessions.filter((x) => x.id !== d);
      if (w.session === d) w.session = w.sessions.length ? w.sessions[w.sessions.length - 1].id : null;
      if (w.agentSession === d) w.agentSession = null;
      render('none');
      break;

    case 'menu-overflow': S.menu = { kind: 'overflow', y: 52, right: 8 }; render('none'); break;
    case 'menu-project': S.menu = { kind: 'project', y: Math.min(ev.clientY - 20, 700), right: 20, data: d }; render('none'); break;
    case 'menu-file': case 'menu-folder': case 'menu-tree':
      S.menu = { kind: a.slice(5), y: Math.min(ev.clientY - 20, 700), x: Math.min(ev.clientX, 180), data: d };
      render('none');
      break;

    case 'allow': S.sheet = null; S.ask = null; render('none'); if (S.pending) { const f = S.pending; S.pending = null; f(true); } break;
    case 'deny': S.sheet = null; S.ask = null; render('none'); if (S.pending) { const f = S.pending; S.pending = null; f(false); } break;
    case 'switch-bypass':
      S.agent = 'bypass'; S.sheet = null; S.ask = null; render('none');
      if (S.pending) { const f = S.pending; S.pending = null; f(true); }
      break;
    // From Rules' own way out of asking: tick the rule, and this call goes ahead.
    case 'allow-rule':
      S.rules[d] = true; S.sheet = null; S.ask = null; render('none');
      if (S.pending) { const f = S.pending; S.pending = null; f(true); }
      break;

    case 'add-note':
      S.note = { scope: 'project', key: '', value: '', editing: null, error: null, message: null };
      openSheet('note');
      break;
    case 'edit-note': {
      const note = w.memory.find((f) => f.key === d);
      if (!note) break;
      S.note = { scope: note.scope, key: note.key, value: note.value, editing: note.key, error: null, message: null };
      openSheet('note');
      break;
    }
    case 'set-note-scope': S.note.scope = d; render('none'); break;
    case 'save-note': saveNote(); break;
    case 'forget':
      w.memory = w.memory.filter((f) => f.key !== d);
      if (S.sheet === 'note') { S.note = null; openSheet('memory'); } else render('none');
      break;
    case 'copy': toast('Скопировано', 'copy'); break;
    case 'toast': toast(d + ' — в прототипе не реализовано', 'info'); break;
    default: break;
  }
}

function onClick(ev) {
  const el = ev.target.closest('[data-a]');
  if (!el) return;
  ev.stopPropagation();
  const a = el.dataset.a;
  if (!a) return;
  act(a, el.dataset.d || '', ev);
}

function onInput(ev) {
  const el = ev.target;
  if (!el.dataset.i) return;
  S.focus = { id: el.id, caret: el.selectionStart };
  const w = S.project ? ws() : null;
  switch (el.dataset.i) {
    case 'search': S.search = el.value; render('none'); break;
    case 'create': S.create.input = el.value; S.create.error = null; render('none'); break;
    case 'clone': S.clone.input = el.value; S.clone.error = null; render('none'); break;
    case 'model-search': S.modelSearch = el.value; render('none'); break;
    case 'history-search': S.historySearch = el.value; render('none'); break;
    // A new query starts again from the newest match.
    case 'find': if (w && w.find) { w.find.q = el.value; w.find.i = null; } render('none'); break;
    case 'chat-rename': S.rename = el.value; render('none'); break;
    case 'form-name': S.form.name = el.value; render('none'); break;
    case 'form-url': S.form.url = el.value; render('none'); break;
    case 'form-headers': S.form.headers = el.value; render('none'); break;
    case 'form-key': S.form.keyInput = el.value; S.form.error = null; render('none'); break;
    case 'note-key': S.note.key = el.value; S.note.error = null; S.note.message = null; render('none'); break;
    case 'note-value': S.note.value = el.value; S.note.error = null; S.note.message = null; render('none'); break;
    case 'draft': w.draft = el.value; autosize(); updateSend(); break;
    default: break;
  }
}

/**
 * The send button and the commands plaque alone, so typing does not repaint
 * the transcript. The plaque takes the changes bar's place; a transcript at
 * its end stays there as the plaque comes and goes.
 */
function updateSend() {
  const w = ws();
  const wrap = document.querySelector('.composer-wrap');
  if (!wrap) return;
  const transcript = document.querySelector('.transcript');
  const following = transcript && transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 24;
  const plaque = commandsHtml(w);
  const old = wrap.querySelector('.cmds');
  if (old && plaque) old.outerHTML = plaque;
  else if (old) old.remove();
  else if (plaque) wrap.insertAdjacentHTML('afterbegin', plaque);
  const bar = document.querySelector('.changes');
  if (bar) bar.hidden = !!plaque;
  const btn = wrap.querySelector('.send');
  if (btn) btn.outerHTML = sendHtml(w);
  if (following) transcript.scrollTop = transcript.scrollHeight;
}

/* ============================================================ simulation */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const LIVE_THINK = 'Сначала узнаю, какие тесты есть и как их запускать. Чтение файлов поручу субагенту, чтобы оно не заняло мой контекст, а pytest запущу сам.';
const LIVE_TEXT = 'Посмотрю, какие тесты есть, и запущу их.';

/** The subagent the simulated turn hands its first question to: an Explorer, which only reads. */
const SIM_SUB = {
  agent: 'explorer',
  prompt: 'Найди в проекте все тесты: в каких они файлах, что проверяют и какой командой запускаются. Ничего не запускай и не меняй. Ответь коротко.',
  files: ['tests/'],
  steps: [
    { t: 'tool', name: 'project_tree', ms: 38,
      body: { kind: 'text', text: 'bot/\n  __init__.py\n  handlers.py\n  main.py\ntests/\n  test_handlers.py\nrequirements.txt' } },
    { t: 'tool', name: 'fs_glob', target: 'tests/**/test_*.py', ms: 21, body: { kind: 'text', text: 'tests/test_handlers.py' } },
    { t: 'tool', name: 'fs_read', target: 'tests/test_handlers.py', ms: 9 },
    { t: 'tool', name: 'fs_read', target: 'requirements.txt', ms: 7 },
  ],
  answer: 'Тест один — `test_echo_repeats_text` в `tests/test_handlers.py`: подменяет `Message.answer` и проверяет, что бот повторяет текст. Запуск: `python -m pytest -q`. pytest и pytest-asyncio в `requirements.txt` нет, они ставятся отдельно.',
};

/** Stream [text] as the agent's words, then keep it as a part of [turn]. False if the turn was stopped. */
async function streamText(w, turn, text, alive) {
  w.live.text = '';
  for (let i = 0; i < text.length; i += 2) {
    if (!alive()) return false;
    w.live.text = text.slice(0, i + 2);
    render('none');
    await sleep(26);
  }
  turn.parts.push({ t: 'text', md: text });
  w.live.text = null;
  return true;
}

/**
 * A subagent's run, as the card shows it live: the main model writing the
 * task, the approval where the mode asks for one, the subagent's steps one
 * after another, its answer as it writes it. 'done', 'denied' or 'stopped'.
 */
async function runSubagent(w, turn, alive) {
  const sub = { t: 'sub', agent: SIM_SUB.agent, prompt: '', files: SIM_SUB.files, steps: [], write: true, ms: 0 };
  turn.parts.push(sub);
  const writing = Date.now();
  for (let i = 0; i < SIM_SUB.prompt.length; i += 5) {
    if (!alive()) return 'stopped';
    sub.prompt = SIM_SUB.prompt.slice(0, i + 5);
    sub.ms = Date.now() - writing;
    render('none');
    await sleep(30);
  }
  sub.prompt = SIM_SUB.prompt;
  sub.write = false;

  // Handing a task on is a change: Confirm asks, From Rules unless subagents
  // are ticked, Bypass Mode never. An Explorer asks nothing after that.
  if (asks('subagents')) {
    sub.wait = true;
    S.ask = { kind: 'sub', agent: sub.agent, prompt: sub.prompt };
    const allowed = await new Promise((resolve) => { S.pending = resolve; openSheet('approval'); });
    S.ask = null;
    sub.wait = false;
    if (!alive()) return 'stopped';
    if (!allowed) {
      sub.denied = true;
      sub.ok = false;
      render('none');
      return 'denied';
    }
  }

  const started = Date.now();
  sub.run = true;
  sub.ms = 0;
  const tick = async (ms) => {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      if (!alive()) return false;
      sub.ms = Date.now() - started;
      render('none');
      await sleep(120);
    }
    return true;
  };
  // Its own model first, then each call as it makes it.
  if (!await tick(500)) return 'stopped';
  for (const s of SIM_SUB.steps) {
    const step = { ...s, run: true };
    sub.steps.push(step);
    if (!await tick(380)) return 'stopped';
    step.run = false;
    step.ok = true;
    if (!await tick(160)) return 'stopped';
  }
  sub.answering = true;
  sub.answer = '';
  for (let i = 0; i < SIM_SUB.answer.length; i += 6) {
    if (!alive()) return 'stopped';
    sub.answer = SIM_SUB.answer.slice(0, i + 6);
    sub.ms = Date.now() - started;
    render('none');
    await sleep(24);
  }
  sub.answer = SIM_SUB.answer;
  sub.answering = false;
  sub.run = false;
  sub.ok = true;
  sub.ms = Date.now() - started;
  render('none');
  return 'done';
}
const LIVE_OUT = '\u001b[32m.\u001b[0m                                                                  [100%]\n\u001b[32m1 passed\u001b[0m in 0.39s';

/* ------------------------------------------------------------ /compact, run */

/** The summary the sample chat folds into: what ConversationDigest holds for it. */
const SAMPLE_DIGEST = {
  objective: 'Эхо-бот на aiogram 3: токен из `.env`, логирование, тест на эхо-обработчик.',
  state: 'Бот работает. Двойные ответы были из-за роутера, подключённого дважды: лишний `dp.include_router(router)` убран из `bot/main.py`, тесты зелёные.',
  decisions: [
    'Токен только в `.env` через `python-dotenv`, в коде его нет: так просил пользователь.',
    'Обработчик без фильтра стоит последним, иначе он перехватит `/start`.',
    'Тест подменяет `Message.answer` через `model_construct`: живой `Message` собирать неудобно.',
  ],
  edits: [
    '`bot/handlers.py` — роутер: `/start` и эхо любого текста.',
    '`bot/main.py` — запуск, `logging` на INFO, один `include_router`.',
    '`tests/test_handlers.py` — `test_echo_repeats_text`.',
    '`requirements.txt` — `aiogram`, `python-dotenv`.',
  ],
  commands: [
    '`python -m pytest -q` → 1 passed.',
    '`pip install aiogram python-dotenv` → установлено; колёса собираются около 20 с.',
  ],
  failed: [],
  kept: [],
  next: [],
};

/** What the sample's summary keeps in detail when asked to keep the token. */
const SAMPLE_KEPT_TOKEN = '`BOT_TOKEN` читается в `main()`: `load_dotenv()`, затем `os.environ["BOT_TOKEN"]`. `.env` в `.gitignore`, пустой пример — `.env.example`.';

/** The one for the links and shots that show a summary kept in detail. */
const DEMO_FOCUS = 'подробности про токен';

/**
 * The summary of [turns], with [focus] kept in detail, taking in the chat's
 * last summary as the app's Compactor merges into it: the sample's own for its
 * chat, a sketch from the words of any other.
 */
function digestOf(w, turns, focus) {
  const asks = turns.filter((t) => t.role === 'user' && t.text).map((t) => t.text);
  const sample = S.project === 'telegram-bot' && asks[0] && asks[0].startsWith('Сделай эхо-бота');
  const kept = !focus ? [] : (sample && /токен/i.test(focus) ? [SAMPLE_KEPT_TOKEN] : [`«${focus}»: подробности из разговора, как они были сказаны.`]);
  let fresh;
  if (sample) {
    fresh = Object.assign({}, SAMPLE_DIGEST, { kept });
  } else {
    const answers = turns.filter((t) => t.role === 'agent').flatMap((t) => (t.parts || []).filter((p) => p.t === 'text').map((p) => p.md));
    fresh = {
      objective: firstLine(asks[0]) || 'Разговор с агентом.',
      state: firstLine(answers[answers.length - 1]) || 'Агент ответил на последний запрос.',
      decisions: [], edits: [], commands: [], failed: [], kept, next: [],
    };
  }
  const prev = lastSummary(w);
  if (!prev || !prev.digest) return fresh;
  const p = prev.digest;
  return {
    objective: p.objective || fresh.objective,
    state: fresh.state,
    decisions: p.decisions.concat(fresh.decisions),
    edits: p.edits.concat(fresh.edits),
    commands: p.commands.concat(fresh.commands),
    failed: p.failed.concat(fresh.failed),
    kept: p.kept.concat(fresh.kept),
    next: fresh.next,
  };
}

/** How long the sample's summary takes to write: one request, a forced tool call. */
const COMPACT_MS = 3200;

/** A compaction that has not run yet, of everything [w] has not folded. */
function newSeam(w, by, focus) {
  const turns = unfolded(w);
  return {
    role: 'seam', by, state: 'run', focus: focus || '', started: Date.now(), ms: 0,
    before: w.context, turns,
  };
}

/**
 * [seam] done: the summary written, and the context down to what no
 * compaction shrinks, the summary, and the share [kept] of the conversation
 * that stays as it was (none for /compact).
 */
function foldInto(w, seam, kept) {
  const parts = contextParts(w);
  const digest = digestOf(w, seam.turns, seam.focus);
  const digestTokens = 900 + (seam.focus ? 150 : 0)
    + 60 * (digest.decisions.length + digest.edits.length + digest.commands.length + digest.failed.length
      + digest.kept.length + digest.next.length);
  const after = parts.tools + parts.instructions + parts.memory + digestTokens + Math.round(parts.dialog * (kept || 0));
  delete seam.turns;
  // A summary hardly shorter than what it would replace is not kept, and nothing is folded.
  if (w.context - after < COMPACT_GAIN) {
    seam.state = 'skipped';
    return;
  }
  seam.digest = digest;
  seam.digestTokens = digestTokens;
  seam.freed = w.context - after;
  seam.state = 'done';
  w.context -= seam.freed;
}

/** A failed or stopped compaction is no part of the conversation: what is done next takes it away. */
function dropTransientSeams(w) {
  for (let i = w.chat.length - 1; i >= 0; i--) {
    const t = w.chat[i];
    if (t.role === 'seam' && t.state !== 'done' && t !== w.compacting) w.chat.splice(i, 1);
  }
}

/**
 * /compact [focus]: everything since the last summary folded into a new one,
 * which takes the old one in. The composer's button stops it, and then, as
 * when it fails, nothing has changed.
 */
async function runCompact(w, focus) {
  const why = commandBlocked(w, COMMANDS[0]);
  if (why) { toast(why, 'info'); return; }
  dropTransientSeams(w);
  const seam = newSeam(w, 'command', focus);
  w.draft = '';
  S.focus = null;
  w.chat.push(seam);
  w.busy = true;
  w.compacting = seam;
  render('none');
  while (Date.now() - seam.started < COMPACT_MS) {
    await sleep(250);
    if (w.compacting !== seam) return;
    seam.ms = Date.now() - seam.started;
    render('none');
  }
  w.compacting = null;
  w.busy = false;
  seam.ms = Date.now() - seam.started;
  foldInto(w, seam, 0);
  render('none');
}

async function startTurn() {
  const w = ws();
  const text = w.draft.trim();
  if (!text && !w.attachments.length) return;
  // A command runs instead of being sent.
  const slash = slashOf(text);
  if (slash && slash.exact) { runCommand(w, slash.exact.name, slash.args); return; }
  dropTransientSeams(w);
  const imgs = w.attachments.slice();
  w.chat.push({ role: 'user', text, imgs });
  // The first request names the chat, as AgentLoop does; each one moves it up the history.
  const open = currentChat(w);
  if (open) {
    if (!open.title) open.title = chatTitle(text);
    open.at = Date.now();
  }
  w.draft = '';
  w.attachments = [];
  w.busy = true;
  S.focus = null;
  const turn = { role: 'agent', parts: [] };
  // What the turn has done so far stays on screen above what it does now.
  w.live = { started: Date.now(), waiting: 'Ожидание модели', parts: turn.parts };
  render('none');

  const alive = () => w.busy;
  await sleep(700);
  if (!alive()) return;

  w.live.waiting = null;
  w.live.think = '';
  for (let i = 0; i < LIVE_THINK.length; i += 3) {
    if (!alive()) return;
    w.live.think = LIVE_THINK.slice(0, i + 3);
    render('none');
    await sleep(24);
  }
  const thinkMs = Date.now() - w.live.started;
  turn.parts.push({ t: 'think', ms: thinkMs, text: LIVE_THINK });
  w.live.think = null;
  w.live.text = '';
  for (let i = 0; i < LIVE_TEXT.length; i += 2) {
    if (!alive()) return;
    w.live.text = LIVE_TEXT.slice(0, i + 2);
    render('none');
    await sleep(26);
  }
  turn.parts.push({ t: 'text', md: LIVE_TEXT });
  w.live.text = null;

  // Finding the tests goes to a subagent, whose card shows its run live.
  const outcome = await runSubagent(w, turn, alive);
  if (outcome === 'stopped') return;
  const next = outcome === 'denied' ? 'Хорошо, без субагента: тесты лежат в `tests/`. Запускаю.' : 'Тест один, запускаю.';
  if (!await streamText(w, turn, next, alive)) return;

  // Confirm asks before the command, From Rules unless commands are ticked,
  // Bypass Mode never.
  if (asks('commands')) {
    w.live.waiting = 'Ждёт разрешения';
    const allowed = await new Promise((resolve) => { S.pending = resolve; openSheet('approval'); });
    w.live.waiting = null;
    if (!allowed) {
      turn.parts.push({ t: 'tool', name: 'sh_exec', target: '$ python -m pytest -q', ms: 0, ok: false,
        body: { kind: 'text', text: 'Отклонено пользователем.' } });
      turn.parts.push({ t: 'text', md: 'Понял, команду не запускаю.' });
      finishTurn(turn);
      return;
    }
  }

  // The model writes the call before it runs: the card says so, and the
  // tool's own clock starts only when the call does.
  const writing = Date.now();
  w.live.tool = { t: 'tool', name: 'sh_exec', target: '$ python -m pytest -q', ms: 0, write: true,
    body: { kind: 'term', text: '' } };
  for (let i = 0; i < 9; i++) {
    if (!alive()) return;
    w.live.tool.ms = Date.now() - writing;
    render('none');
    await sleep(140);
  }

  // The chat's transcript appears the first time the agent runs something
  // and is kept for its later commands; a closed one comes back as a new one.
  const session = w.sessions.find((s) => s.id === w.agentSession) || newShell(w, true);
  w.agentSession = session.id;
  session.running = true;
  session.out += `\n\u001b[32m~/projects/${S.project}\u001b[0m $ \u001b[1mpython -m pytest -q\u001b[0m\n`;

  const started = Date.now();
  w.live.tool = { t: 'tool', name: 'sh_exec', target: '$ python -m pytest -q', ms: 0, run: true,
    body: { kind: 'term', text: '' } };
  for (let i = 0; i < 14; i++) {
    if (!alive()) { session.running = false; return; }
    w.live.tool.ms = Date.now() - started;
    if (i === 6) { w.live.tool.body.text = LIVE_OUT.split('\n')[0]; session.out += LIVE_OUT.split('\n')[0] + '\n'; }
    if (i === 11) { w.live.tool.body.text = LIVE_OUT; session.out += LIVE_OUT.split('\n')[1] + '\n'; }
    render('none');
    await sleep(140);
  }
  session.running = false;
  session.out += `\u001b[2m[exit 0 · ${secs(Date.now() - started)}]\u001b[0m\n`;
  turn.parts.push({ t: 'tool', name: 'sh_exec', target: '$ python -m pytest -q', ms: Date.now() - started, ok: true,
    body: { kind: 'term', text: LIVE_OUT } });
  w.live.tool = null;

  turn.parts.push({ t: 'text', md: 'Тесты зелёные — `1 passed`.\n\nДальше этот ход не идёт: **это прототип дизайна**, запросы к модели из него не уходят. В приложении здесь были бы правки файлов и новые карточки инструментов.' });
  finishTurn(turn);
}

/**
 * What the user opened while the turn ran stays open once it lands: the
 * turn's parts are keyed `live.<part>` while it runs and `<turn>.<part>`
 * after, steps and the task's "show all" below them alike.
 */
function keepOpen(w) {
  const i = w.chat.length;
  Object.keys(w.open).filter((k) => k.startsWith('live.')).forEach((k) => {
    w.open[`${i}.${k.slice(5)}`] = w.open[k];
    delete w.open[k];
  });
}

function finishTurn(turn) {
  const w = ws();
  keepOpen(w);
  w.chat.push(turn);
  w.live = null;
  w.busy = false;
  w.context += 1800;
  render('none');
}

function stopTurn() {
  const w = ws();
  if (!w.busy) return;
  // A compaction stopped changes nothing: nothing is folded until its summary is written.
  if (w.compacting) {
    w.compacting.state = 'stopped';
    w.compacting = null;
    w.busy = false;
    render('none');
    return;
  }
  w.busy = false;
  const live = w.live;
  w.live = null;
  // What the turn had done stays; a subagent still at work stops with it.
  const parts = live && live.parts ? live.parts.slice() : [];
  parts.forEach((p) => {
    if (p.t !== 'sub' || !(p.run || p.write || p.wait)) return;
    p.run = p.write = p.wait = p.answering = false;
    p.stopped = true;
    p.ok = false;
    (p.steps || []).forEach((s) => { if (s.run) { s.run = false; s.stopped = true; } });
  });
  if (live && live.think) parts.push({ t: 'think', ms: Date.now() - live.started, text: live.think });
  if (live && live.text) parts.push({ t: 'text', md: live.text });
  parts.push({ t: 'text', md: '_Остановлено._' });
  // What had arrived is in the conversation now, and in the context.
  w.context += 600;
  keepOpen(w);
  S.ask = null;
  w.chat.push({ role: 'agent', parts });
  // Whatever was still running gets the same ^C it would get from the keys
  // row, so the terminal reads the same either way.
  w.sessions.forEach((s) => {
    if (!s.running) return;
    s.running = false;
    s.out += '\u001b[31m^C\u001b[0m\n\u001b[2m[exit 130]\u001b[0m\n'
      + `\u001b[32m~/projects/${S.project}\u001b[0m $ `;
  });
  S.pending = null;
  if (S.sheet === 'approval') S.sheet = null;
  render('none');
}

async function runClone() {
  const parsed = parseGitHub(S.clone.input);
  if (!parsed) {
    S.clone.error = 'Это не репозиторий GitHub: нужно owner/repo или https://github.com/owner/repo.';
    render('none');
    return;
  }
  S.clone.name = parsed.owner + '/' + parsed.repo + (parsed.ref ? '@' + parsed.ref : '');
  const bad = /private|missing|secret/i.test(parsed.repo);
  S.clone.step = 1;
  S.clone.bytes = 0;
  render('none');
  for (let i = 0; i < 12; i++) {
    await sleep(130);
    if (S.sheet !== 'clone') return;
    S.clone.bytes += 180000 + Math.round(Math.random() * 120000);
    render('none');
  }
  if (bad) { S.clone.step = 4; render('none'); return; }
  S.clone.step = 2;
  for (let i = 0; i < 10; i++) {
    await sleep(110);
    if (S.sheet !== 'clone') return;
    S.clone.files += 4 + Math.round(Math.random() * 6);
    render('none');
  }
  S.clone.step = 3;
  const name = parsed.repo;
  if (!S.projects.some((p) => p.name === name)) {
    S.projects.unshift({ name, min: 0, files: S.clone.files, note: 'с GitHub' });
  }
  render('none');
}

function runShell() {
  const w = ws();
  const el = $('termInput');
  const s = w.sessions.find((x) => x.id === w.session);
  if (!el || !s) return;
  const cmd = el.value.trim();
  el.value = '';
  if (!cmd) { s.out += '\n\u001b[32m~/projects/' + S.project + '\u001b[0m $ '; render('none'); return; }
  if (cmd === 'clear') { s.out = '\u001b[32m~/projects/' + S.project + '\u001b[0m $ '; render('none'); return; }
  const out = SHELL_CANNED[cmd];
  s.out += `\u001b[1m${cmd}\u001b[0m\n`
    + (out !== undefined ? out + '\n' : `bash: ${cmd.split(' ')[0]}: в прототипе такой команды нет\n`)
    + `\u001b[32m~/projects/${S.project}\u001b[0m $ `;
  render('none');
}

/* ============================================================ помощь сбоку */

const LINKS = [
  { group: 'Экраны' },
  { text: 'Главное меню', hash: '#/home' },
  { text: 'Первый запуск', hash: '#/home?first' },
  { text: 'Открыть проект', hash: '#/open' },
  { text: 'Создать новый', hash: '#/home/create' },
  { text: 'Клонировать репозиторий', hash: '#/home/clone' },
  { text: 'Настройки', hash: '#/settings' },
  { text: 'Новое подключение', hash: '#/settings/presets' },
  { text: 'Форма подключения', hash: '#/settings/endpoint' },
  { group: 'Проект' },
  { text: 'Агент · диалог', hash: '#/ws/telegram-bot/agent' },
  { text: 'Агент · пустой чат', hash: '#/ws/scraper/agent' },
  { text: 'История чатов', hash: '#/ws/telegram-bot/agent/history' },
  { text: 'История нового проекта', hash: '#/ws/scraper/agent/history' },
  { text: 'Удалить чат', hash: '#/ws/telegram-bot/agent/chat-delete' },
  { text: 'Поиск по чату', hash: '#/ws/telegram-bot/agent?find=' + encodeURIComponent('токен') },
  { text: 'Режим агента', hash: '#/ws/telegram-bot/agent/agent' },
  { text: 'Подтверждение команды', hash: '#/ws/telegram-bot/agent/approval' },
  { text: 'Изменения агента', hash: '#/ws/telegram-bot/agent/changes' },
  { text: 'Вернуться к чекпоинту', hash: '#/ws/telegram-bot/agent/checkpoint' },
  { text: 'Память агента', hash: '#/ws/telegram-bot/agent/memory' },
  { text: 'Новая заметка', hash: '#/ws/telegram-bot/agent/note' },
  { text: 'Выбор модели', hash: '#/ws/telegram-bot/agent/model' },
  { group: 'Сжатие контекста' },
  { text: 'Команды: «/»', hash: '#/ws/telegram-bot/agent?compact=menu' },
  { text: '/compact с уточнением', hash: '#/ws/telegram-bot/agent?compact=typed' },
  { text: 'Сжимает', hash: '#/ws/telegram-bot/agent?compact=run' },
  { text: 'Сжато', hash: '#/ws/telegram-bot/agent?compact=done' },
  { text: 'Сводка открыта', hash: '#/ws/telegram-bot/agent?compact=open' },
  { text: 'Не удалось сжать', hash: '#/ws/telegram-bot/agent?compact=failed' },
  { text: 'Сжато автоматически', hash: '#/ws/telegram-bot/agent?compact=auto' },
  { text: 'Пустой чат: «/»', hash: '#/ws/scraper/agent?compact=menu' },
  { text: 'Использование контекста', hash: '#/ws/telegram-bot/agent/context' },
  { group: 'Проект · вкладки' },
  { text: 'Файлы', hash: '#/ws/telegram-bot/files' },
  { text: 'Редактор', hash: '#/ws/telegram-bot/editor' },
  { text: 'Терминал', hash: '#/ws/telegram-bot/terminal' },
];

function renderPanel() {
  const panel = $('panel');
  if (!panel || document.body.classList.contains('bare')) return;
  const here = hashOf();
  const groups = LINKS.map((l) => l.group
    ? `<div class="gl">${esc(l.group)}</div>`
    : `<button class="link${here === l.hash ? ' on' : ''}" data-h="${l.hash}">${esc(l.text)}</button>`).join('');
  panel.innerHTML = `<h1><span class="logo-mini">${DESIGN.logo}</span>ApkCLI</h1>`
    + '<p>Прототип чёрной темы: те же токены, шрифты, иконки и размеры в dp, что пойдут в приложение. '
    + 'Всё кликается, данные внутри — локальные.</p>'
    + `<div class="group">${groups}</div>`
    + '<div class="group"><div class="gl">Экран</div><div class="opts">'
    + `<button class="opt${S.device === 360 ? ' on' : ''}" data-s="360">360 × 800</button>`
    + `<button class="opt${S.device !== 360 ? ' on' : ''}" data-s="412">412 × 915</button>`
    + '</div></div>'
    + '<div class="group"><div class="gl">Масштаб</div><div class="opts">'
    + `<button class="opt${S.zoom === 'fit' ? ' on' : ''}" data-z="fit">По окну</button>`
    + `<button class="opt${S.zoom !== 'fit' ? ' on' : ''}" data-z="1">100 %</button>`
    + '</div></div>'
    + '<div class="foot">Один файл, без сети. Откройте его на телефоне — рамка пропадёт, и экран будет в натуральную величину.</div>';
}

function deviceVars() {
  const root = document.documentElement;
  root.style.setProperty('--dw', (S.device === 360 ? 360 : 412) + 'px');
  root.style.setProperty('--dh', (S.device === 360 ? 800 : 915) + 'px');
}

function applyDevice() {
  deviceVars();
  const device = $('device');
  if (S.zoom === 'fit') {
    const area = $('deviceArea');
    const w = (S.device === 360 ? 360 : 412) + 20;
    const h = (S.device === 360 ? 800 : 915) + 20;
    const scale = Math.min(1, (area.clientWidth - 48) / w, (area.clientHeight - 48) / h);
    device.style.zoom = scale.toFixed(3);
  } else {
    device.style.zoom = '1';
  }
}

/* ================================================================== boot */

function fitMode() {
  const bare = S.shot || window.innerWidth <= 520;
  document.body.classList.toggle('bare', bare);
  document.body.classList.toggle('shot', !!S.shot);
  deviceVars();
  if (!bare) applyDevice();
}

function tickClock() {
  const d = new Date();
  $('clock').textContent = `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
}

$('sysIcons').innerHTML =
  '<svg width="16" height="16" viewBox="0 0 16 16"><path d="M8 13.2 1.1 6.3A9.8 9.8 0 0 1 8 3.5c2.6 0 5 1 6.9 2.8L8 13.2Z" opacity=".9"/></svg>'
  + '<svg width="22" height="12" viewBox="0 0 22 12"><rect x="1" y="1" width="18" height="10" rx="3" fill="none" stroke="currentColor" stroke-opacity=".5"/>'
  + '<rect x="2.5" y="2.5" width="12" height="7" rx="2"/><path d="M20.5 4.2v3.6a2 2 0 0 0 0-3.6Z" opacity=".5"/></svg>';

document.addEventListener('click', (ev) => {
  const link = ev.target.closest('[data-h]');
  if (link) { location.hash = link.dataset.h; return; }
  const size = ev.target.closest('[data-s]');
  if (size) { S.device = +size.dataset.s; applyDevice(); renderPanel(); return; }
  const zoom = ev.target.closest('[data-z]');
  if (zoom) { S.zoom = zoom.dataset.z; applyDevice(); renderPanel(); return; }
});

$('viewport').addEventListener('click', onClick);
$('viewport').addEventListener('input', onInput);
// The phone's long press: a file's menu, a folder's, or the project's from
// the empty space below the rows.
$('viewport').addEventListener('contextmenu', (ev) => {
  const row = ev.target.closest('.tr');
  const space = !row && ev.target.closest('.tree, .explorer-empty');
  if (!row && !space) return;
  ev.preventDefault();
  if (space) act('menu-tree', '', ev);
  else act(row.dataset.a === 'fold' ? 'menu-folder' : 'menu-file', row.dataset.d, ev);
});
$('viewport').addEventListener('keydown', (ev) => {
  // Rows and row controls are divs with role="button", so they need this.
  if ((ev.key === 'Enter' || ev.key === ' ') && ['button', 'checkbox'].includes(ev.target.getAttribute('role'))) {
    ev.preventDefault();
    ev.target.click();
    return;
  }
  if (ev.target.id === 'termInput' && ev.key === 'Enter') { ev.preventDefault(); runShell(); }
  // The keyboard's search key steps back to the next older match; Esc closes.
  if (ev.target.id === 'findInput' && ev.key === 'Enter') { ev.preventDefault(); act('find-prev', ''); }
  if (ev.target.id === 'findInput' && ev.key === 'Escape') { ev.preventDefault(); act('find-close', ''); }
  if (ev.target.id === 'draft' && ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); startTurn(); }
  if (ev.target.id === 'cloneInput' && ev.key === 'Enter') { ev.preventDefault(); act('do-clone', ''); }
  if (ev.target.id === 'createInput' && ev.key === 'Enter') { ev.preventDefault(); act('do-create', ''); }
});
$('viewport').addEventListener('scroll', (ev) => {
  const el = ev.target;
  if (el.dataset && el.dataset.keep) {
    S.scroll[el.dataset.keep] = { top: el.scrollTop, follow: el.scrollHeight - el.scrollTop - el.clientHeight < 24 };
  }
}, true);

$('filePick').addEventListener('change', (ev) => {
  const w = ws();
  for (const file of ev.target.files) {
    if (w.attachments.length >= 6) break;
    w.attachments.push(URL.createObjectURL(file));
  }
  ev.target.value = '';
  render('none');
});

window.addEventListener('hashchange', () => {
  if (location.hash === wroteHash) return;
  applyHash();
});
window.addEventListener('resize', () => { fitMode(); });

S.zoom = 'fit';
S.device = 412;
S.shot = /[?&]shot(&|$)/.test(location.hash);
fitMode();
tickClock();
setInterval(tickClock, 20000);
applyHash();
// The first repaint can lay text out in a fallback face while the bundled
// fonts decode, and the swap then leaves the chat short of its end. Once
// they are in, views that follow their end go back to it -- unless the
// link was to a match or a card, which applyFind and revealOnce placed.
document.fonts.ready.then(() => {
  if (S.revealed || (S.route.name === 'ws' && ws().find)) return;
  document.querySelectorAll('#viewport [data-follow]').forEach((el) => {
    const state = S.scroll[el.dataset.keep];
    if (!state || state.follow) el.scrollTop = el.scrollHeight;
  });
});
