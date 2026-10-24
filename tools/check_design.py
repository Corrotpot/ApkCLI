#!/usr/bin/env python3
"""Smoke-test the design prototype: click everything, report any JS error.

    python tools/check_design.py [build/design/ApkCLI-design.html]

Takes the built page, injects an error collector and a script that visits each
route and clicks every control on it, then runs it in headless Chrome and
prints what broke. Catches the ordinary mistakes -- a typo'd action, an icon
that is not there, a null dereference -- which a screenshot does not show.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")

ROUTES = [
    "/home", "/home?first", "/home/create", "/home/clone", "/open", "/settings",
    "/ws/telegram-bot/agent", "/ws/scraper/agent", "/ws/telegram-bot/agent/approval",
    "/ws/telegram-bot/agent/changes", "/ws/telegram-bot/agent/memory",
    "/ws/telegram-bot/agent/note",
    "/ws/telegram-bot/agent/model", "/ws/telegram-bot/agent/agent",
    "/ws/telegram-bot/agent/reasoning", "/ws/telegram-bot/agent/context",
    "/ws/telegram-bot/agent/history", "/ws/scraper/agent/history",
    "/ws/telegram-bot/agent?find=aiogram", "/ws/telegram-bot/agent?find=zzz-no-match",
    "/ws/telegram-bot/agent/chat-rename", "/ws/telegram-bot/agent/chat-delete",
    "/ws/telegram-bot/agent?sub=open", "/ws/telegram-bot/agent?sub=live",
    "/ws/telegram-bot/agent?sub=live-open", "/ws/telegram-bot/agent?sub=writing",
    "/ws/telegram-bot/agent/approval?sub", "/ws/telegram-bot/agent?find=include_router",
    "/ws/telegram-bot/agent?compact=menu", "/ws/telegram-bot/agent?compact=typed",
    "/ws/telegram-bot/agent?compact=run", "/ws/telegram-bot/agent?compact=done",
    "/ws/telegram-bot/agent?compact=open", "/ws/telegram-bot/agent?compact=failed",
    "/ws/telegram-bot/agent?compact=auto", "/ws/scraper/agent?compact=menu",
    "/ws/telegram-bot/agent?compact=open&find=BOT_TOKEN",
    "/ws/telegram-bot/files", "/ws/telegram-bot/editor", "/ws/telegram-bot/terminal",
    "/settings/presets", "/settings/endpoint", "/settings/confirm",
]

PROBE = """
<div id="PROBE">running</div>
<script>
const ERRORS = [];
window.addEventListener('error', (e) => ERRORS.push('error: ' + e.message));
window.addEventListener('unhandledrejection', (e) => ERRORS.push('reject: ' + e.reason));
const ROUTES = __ROUTES__;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
// Straight to the app's own router: waiting on a hashchange event races the
// repaints that timers schedule, and this is a test, not a user.
// applyHash twice: the first lands the state, the second re-pins it after the
// event queue has drained, so a repaint left over from the previous click
// cannot leave us somewhere else.
const goto = async (route) => {
  // A held compaction is put on the sample's own chat: a compaction the
  // click before ran for real leaves nothing new to fold, and the held one
  // would not appear.
  if (route.includes('compact=')) delete S.ws[route.split('/')[2]];
  location.hash = '#' + route;
  applyHash();
  await wait(30);
  applyHash();
  await wait(10);
};

(async () => {
  let clicks = 0;
  for (const route of ROUTES) {
    await goto(route);
    const names = [...new Set([...document.querySelectorAll('[data-a]')]
      .map((el) => el.dataset.a).filter(Boolean))];
    if (!names.length) ERRORS.push('no controls on ' + route);
    for (const name of names) {
      await goto(route);
      const el = document.querySelector('[data-a="' + name + '"]');
      if (!el) continue;
      try { el.click(); } catch (e) { ERRORS.push(route + ' ' + name + ': ' + e.message); }
      clicks++;
      await wait(30);
      // An empty screen means the click left nothing to draw.
      if (!document.querySelector('#viewport .screen')) {
        ERRORS.push(route + ' ' + name + ': no screen after click');
      }
    }
  }
  // Typing, which the click pass never reaches. Opened through the app's own
  // action rather than the hash, so a sheet animating out cannot race it.
  await goto('/home');
  act('sheet-clone', '');
  await wait(60);
  for (const text of ['torvalds/linux', 'https://github.com/rsms/inter/tree/master', 'nope']) {
    const input = document.getElementById('cloneInput');
    if (!input) {
      const title = document.querySelector('.sheet-title');
      ERRORS.push('no clone input (hash ' + location.hash + ', sheet ' + S.sheet
        + ', step ' + S.clone.step + ', title ' + (title ? title.textContent : 'none') + ')');
      break;
    }
    input.value = text;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await wait(40);
    const parsed = document.querySelector('.parsed');
    const ok = parsed && parsed.textContent.trim().length > 0;
    if (ok === (text === 'nope')) ERRORS.push('clone parse wrong for ' + text);
  }
  // Pasting a credential: the shape is checked, then /models is fetched.
  await goto('/settings');
  act('pick-preset', 'anthropic');
  await wait(60);
  const paste = async (text) => {
    const input = document.getElementById('formKey');
    if (!input) { ERRORS.push('no key field'); return false; }
    input.value = text;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await wait(40);
    act('save-endpoint', '');
    await wait(60);
    return true;
  };
  if (await paste('nope')) {
    if (!document.querySelector('.helper.error')) ERRORS.push('a wrong prefix was accepted');
    if (await paste('sk-ant-api03-' + 'x'.repeat(24))) {
      await wait(1400);
      const saved = S.endpoints.find((e) => e.preset === 'anthropic');
      if (!saved) ERRORS.push('the connection was not saved');
      else if (!saved.models.length) ERRORS.push('no models were fetched');
      else if (saved.loading) ERRORS.push('still loading models');
    }
  }

  // A whole simulated turn: thinking, a subagent, a command, the final answer.
  // Under Bypass Mode, whatever mode the click pass left behind: Confirm
  // would stop the turn at an approval nobody answers.
  // Land on the agent tab explicitly, and finish any turn the click pass
  // started, so this stage does not inherit the previous one's state.
  // The click pass may have opened, started or deleted chats: this stage
  // starts from the sample's own chat.
  if (S.ws['telegram-bot'] && S.ws['telegram-bot'].busy) { location.hash = '#/ws/telegram-bot/agent'; applyHash(); stopTurn(); await wait(80); }
  delete S.ws['telegram-bot'];
  await goto('/ws/telegram-bot/agent');
  act('tab', 'agent');
  if (W('telegram-bot').busy) { stopTurn(); await wait(80); }
  await wait(40);
  const draft = document.getElementById('draft');
  if (!draft) {
    ERRORS.push('no composer (hash ' + location.hash + ', route ' + S.route.name
      + ', project ' + S.project + ', tab ' + (S.project ? W(S.project).tab : '-')
      + ', screens ' + document.querySelectorAll('#viewport .screen').length + ')');
  }
  else {
    S.agent = 'bypass';
    draft.value = 'Проверь тесты';
    draft.dispatchEvent(new Event('input', { bubbles: true }));
    await wait(40);
    const send = document.querySelector('.send[data-a="send"]');
    if (!send || send.disabled) ERRORS.push('send stayed disabled');
    else {
      send.click();
      // Midway, the subagent's card is on screen and running.
      await wait(5000);
      if (!document.querySelector('#viewport .tool.sub.live')) ERRORS.push('no running subagent card midway');
      await wait(11000);
      const turns = document.querySelectorAll('#viewport .transcript > *').length;
      if (turns < 6) ERRORS.push('turn did not land: ' + turns + ' blocks');
      if (document.querySelector('.send.stop')) ERRORS.push('still busy after the turn');
      const landed = W('telegram-bot').chat.slice(-1)[0];
      const sub = landed && landed.parts && landed.parts.find((p) => p.t === 'sub');
      if (!sub || !sub.ok || !sub.answer || sub.steps.length !== 4) ERRORS.push('the subagent did not finish in the landed turn');
    }
  }

  // /compact: the plaque as the words are typed, a path left alone, the
  // command run by sending it, its seam, and the context after.
  {
    const w = W('telegram-bot');
    if (w.busy) { stopTurn(); await wait(80); }
    const type = async (text) => {
      const el = document.getElementById('draft');
      if (!el) { ERRORS.push('no composer for /compact'); return false; }
      el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      await wait(40);
      return true;
    };
    if (await type('/co')) {
      if (!document.querySelector('.cmds .cmd[data-d="compact"]')) ERRORS.push('no /compact in the plaque for "/co"');
      await type('/etc/hosts не читается');
      if (document.querySelector('.cmds')) ERRORS.push('a path opened the commands plaque');
      const plain = document.querySelector('.send[data-a="send"]');
      if (!plain || plain.getAttribute('aria-label') !== 'Отправить') ERRORS.push('a path is not sent as a message');
      await type('/compact про токен');
      const send = document.querySelector('.send[data-a="send"]');
      if (!send || send.disabled || send.getAttribute('aria-label') !== 'Сжать контекст') ERRORS.push('the send button does not run the command');
      const before = w.context;
      const asked = w.chat.filter((t) => t.role === 'user').length;
      if (send) send.click();
      await wait(300);
      if (!document.querySelector('#viewport .seam.run')) ERRORS.push('no compaction running');
      else if (document.querySelector('#viewport .seam.run .seam-sub')) ERRORS.push('the running seam is more than its line');
      if (w.chat.filter((t) => t.role === 'user').length !== asked) ERRORS.push('the command was sent as a message');
      await wait(4200);
      const seam = w.chat[w.chat.length - 1];
      if (!seam || seam.role !== 'seam' || seam.state !== 'done') ERRORS.push('the compaction did not finish');
      else {
        if (seam.focus !== 'про токен') ERRORS.push('what to keep was lost: ' + seam.focus);
        if (!(w.context < before)) ERRORS.push('the context did not shrink');
        if (w.busy) ERRORS.push('still busy after compacting');
      }
      const done = document.querySelectorAll('#viewport .seam.done');
      if (!done.length) ERRORS.push('no seam after compacting');
      else if (seam && seam.freed) {
        // What it freed, and only that, under the line.
        const caption = done[done.length - 1].querySelector('.seam-sub');
        const said = caption ? caption.textContent : '';
        if (said !== 'Освобождено: ' + tokens(seam.freed)) ERRORS.push('the seam says "' + said + '", not what it freed');
      }
      await type('/compact');
      const again = document.querySelector('.cmds .cmd[data-d="compact"]');
      if (!again || !again.disabled) ERRORS.push('a second /compact with nothing new was not blocked');
      await type('');
    }
  }
  document.getElementById('PROBE').textContent =
    (ERRORS.length ? 'FAIL ' + ERRORS.length + ' :: ' + ERRORS.join(' :: ') : 'OK') + ' (' + clicks + ' clicks)';
})();
</script>
"""


def main() -> int:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/design/ApkCLI-design.html"
    html = source.read_text(encoding="utf-8")
    probe = PROBE.replace("__ROUTES__", json.dumps(ROUTES))
    with tempfile.TemporaryDirectory() as tmp:
        page = Path(tmp) / "check.html"
        page.write_text(html.replace("</body>", probe + "</body>"), encoding="utf-8")
        # --disable-ipc-flooding-protection: past a few hundred hash changes in
        # seconds, Chrome starts ignoring them ("Throttling navigation to
        # prevent the browser from hanging"), and every route after that is
        # tested on whatever screen the last one left.
        result = subprocess.run(
            [str(CHROME), "--headless=new", "--window-size=460,960", "--virtual-time-budget=90000",
             "--disable-ipc-flooding-protection", "--dump-dom", page.as_uri() + "#/home"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300,
        )
    match = re.search(r'<div id="PROBE">(.*?)</div>', result.stdout, re.S)
    if not match:
        print("error: the page never reported back", file=sys.stderr)
        return 2
    report = match.group(1).strip()
    print(report.replace(" :: ", "\n  "))
    return 0 if report.startswith("OK") else 1


if __name__ == "__main__":
    sys.exit(main())
