# Security policy

**English** · [Русский](SECURITY.ru.md)

## Reporting a vulnerability

Report it privately, through GitHub's form
[Report a vulnerability](https://github.com/Corrotpot/ApkCLI/security/advisories/new).
Only the maintainer can read what is sent there. Please do not open a public
issue, pull request or discussion about it.

A useful report says:

* what an attacker can do, and what they need first: a web page the agent
  reads, a file in a project, a hostile endpoint, another app on the device;
* how to reproduce it, or a proof of concept;
* the build flavour (play or power), the commit, and the device or emulator
  with its Android version.

Never put a real key or token in a report. If one has leaked, revoke it at the
provider first.

There is no guaranteed response time. The answer comes in the report itself,
and once a fix is out it is published as a security advisory, crediting the
reporter if they want to be named.

## Supported versions

There are no releases yet. Fixes go to the latest commit on `main` only.

## In scope

ApkCLI runs an AI agent with a shell, the files of your projects and your API
keys. These are vulnerabilities:

* An API key or account token leaving the app other than to the connection it
  belongs to: in logs, backups, exports, or sent to another host.
* A key sent over plain HTTP to a public host. Plain HTTP is allowed only to
  private addresses (`NetworkHosts`).
* A tool call that changes something running without the approval its mode
  requires: any change under Confirm, or an unticked kind of change under From
  Rules.
* Reaching files outside `~/projects` through the documents provider
  (`ProjectDocuments`), or writing outside the target folder while a project is
  imported (`..` in paths, symlinks).
* Another app on the device reaching this app's data, keys or shell through an
  exported component.

## Out of scope

* What Bypass Mode does. It asks about nothing by design, and its description
  says so.
* Prompt injection on its own. A page or a file that talks the model into
  something is a known property of LLM agents; it becomes a vulnerability when
  it gets past an approval the mode should have asked for.
* What a model provider does with the requests sent to it.
* Vulnerabilities in Termux packages installed with `pkg_install`. Report them
  to [Termux](https://github.com/termux/termux-packages).
* Attacks that need root, or an unlocked device in the attacker's hands.
* The power build targeting API 28. That is what lets it run installed
  programs; see [docs/ANDROID_EXEC_NOTES.md](docs/ANDROID_EXEC_NOTES.md).
