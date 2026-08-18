// Self-update: on a timer, check whether new brain code has landed on
// GitHub main and, if so, pull it, npm-install only if the lock file
// changed, then hand off to the caller's restart hook (which is expected to
// wait for the scheduler/actor queues to go idle, run the existing stop()
// path, then process.exit(75) - exit code 75 = "restart me", understood by
// run-forever.cmd).
//
// Deliberately dependency-free: shells out to plain `git` (rev-parse,
// ls-remote, pull --ff-only, diff) via child_process.execFile rather than
// anything that would need a GitHub API token or an extra npm package.
//
// The `runner` (git/npm exec) is injectable so test/smoke.mjs can drive the
// decision logic - same-hash/different-hash/pull-failure/non-git-dir - with
// canned results and no real network or git calls. See SelfUpdater below.

import { execFile } from "node:child_process";
import path from "node:path";
import { log } from "./logger.mjs";
import { BRAIN_ROOT } from "./config.mjs";

/** Default injectable runner: run `cmd args...` with cwd `opts.cwd`, resolve
 * {stdout, stderr} on exit code 0, reject (with .stdout/.stderr attached) on
 * nonzero exit or spawn failure. */
export function defaultRunner(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { cwd: opts.cwd, windowsHide: true }, (err, stdout, stderr) => {
      if (err) {
        err.stdout = String(stdout || "");
        err.stderr = String(stderr || "");
        reject(err);
        return;
      }
      resolve({ stdout: String(stdout || ""), stderr: String(stderr || "") });
    });
  });
}

/** File(s) whose change in the pulled range means "run npm install".
 * package-lock.json lives in brain/, so its path relative to the repo root
 * (git diff --name-only's output) is "brain/package-lock.json". */
const LOCKFILE_PATHS = new Set(["brain/package-lock.json", "package-lock.json"]);

export class SelfUpdater {
  /**
   * @param {object} opts
   * @param {number} opts.checkIntervalSec - seconds between checks; <=0 disables entirely.
   * @param {string} [opts.repoRoot] - git checkout root (brain/..).
   * @param {string} [opts.brainDir] - where `npm install` runs (brain/).
   * @param {() => Promise<void>} [opts.waitForIdle] - resolves once no
   *   director scene / actor turn is in flight. Defaults to resolving
   *   immediately (only relevant for tests that skip index.mjs's wiring).
   * @param {number} [opts.idleWaitCapSec] - hard cap (seconds) on how long
   *   to wait for waitForIdle() before restarting anyway. Without this, a
   *   wedged director scene or actor turn (see lib/timed-query.mjs's own,
   *   independent turn timeout - should now be rare) would block
   *   self-update forever, since waitForIdle() previously had no way to
   *   give up. <=0 disables the cap (waits forever, old behavior).
   * @param {() => (void | Promise<void>)} [opts.restart] - called once idle
   *   (or once the idle-wait cap is hit); expected to run the existing
   *   stop() path then process.exit(75).
   * @param {(cmd: string, args: string[], opts: {cwd: string}) => Promise<{stdout:string, stderr:string}>} [opts.runner]
   *   injectable process runner, for tests.
   */
  constructor({
    checkIntervalSec,
    repoRoot = path.resolve(BRAIN_ROOT, ".."),
    brainDir = BRAIN_ROOT,
    waitForIdle = () => Promise.resolve(),
    idleWaitCapSec = 600,
    restart = () => process.exit(75),
    runner = defaultRunner,
  } = {}) {
    this.checkIntervalSec = checkIntervalSec;
    this.repoRoot = repoRoot;
    this.brainDir = brainDir;
    this.waitForIdle = waitForIdle;
    this.idleWaitCapSec = idleWaitCapSec;
    this.restart = restart;
    this.runner = runner;
    this.timer = null;
    this._checking = false; // reentrancy guard: never overlap two checks
  }

  /** Awaits waitForIdle(), but never longer than idleWaitCapSec (<=0 = no
   * cap, waits forever). Returns { capped } - true when the cap fired before
   * waitForIdle() resolved, i.e. we are about to restart with a turn
   * presumed still in flight. */
  async _waitForIdleWithCap() {
    const capMs = Math.max(0, Number(this.idleWaitCapSec) || 0) * 1000;
    if (!capMs) {
      await this.waitForIdle();
      return { capped: false };
    }
    let idleReached = false;
    const idle = Promise.resolve(this.waitForIdle()).then(() => {
      idleReached = true;
    });
    // Deliberately NOT unref'd (unlike this file's periodic check timer):
    // this is a short, bounded wait we actually need to fire to make
    // forward progress, not a background timer that should let the process
    // exit early if nothing else is pending.
    let timer;
    const cap = new Promise((resolve) => {
      timer = setTimeout(resolve, capMs);
    });
    await Promise.race([idle, cap]);
    clearTimeout(timer);
    if (idleReached) return { capped: false };
    // The cap fired first - never await `idle` itself (it may never
    // resolve at all for a genuinely wedged turn); just note its eventual
    // settlement can't produce an unhandled rejection.
    idle.catch(() => {});
    return { capped: true };
  }

  /** Start the periodic timer. No-op (logged) if disabled via <=0 seconds. */
  start() {
    if (!this.checkIntervalSec || this.checkIntervalSec <= 0) {
      log.info("self_update_disabled", { reason: "BRAIN_UPDATE_CHECK_SEC=0" });
      return this;
    }
    const intervalMs = this.checkIntervalSec * 1000;
    this.timer = setInterval(() => {
      // Reentrancy: checkOnce() below no-ops immediately (returns
      // "skipped_already_checking") if a prior check is still in flight, so
      // a check slower than the interval (e.g. a 15s network hiccup against
      // a 10s timer) simply drops the overlapping tick rather than queuing
      // a pile of pending checks behind it.
      this.checkOnce().catch((e) =>
        log.error("self_update_check_crashed", { error: String((e && e.stack) || e) })
      );
    }, intervalMs);
    // Never keep the process alive on its own - matches every other
    // recurring timer in this codebase (director-scheduler, lore watcher).
    if (typeof this.timer.unref === "function") this.timer.unref();
    return this;
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async _git(args) {
    return this.runner("git", args, { cwd: this.repoRoot });
  }

  /** Run one check-and-maybe-update-and-restart cycle. Returns a small
   * result object describing what happened, mainly so tests can assert on
   * the decision without scraping logs. Never throws - every failure path
   * logs and returns instead. */
  async checkOnce() {
    if (this._checking) return { action: "skipped_already_checking" };
    this._checking = true;
    try {
      return await this._runCheck();
    } finally {
      this._checking = false;
    }
  }

  async _runCheck() {
    try {
      await this._git(["rev-parse", "--is-inside-work-tree"]);
    } catch {
      log.info("self_update_disabled", { reason: "not a git checkout" });
      return { action: "disabled_not_git" };
    }

    let localHead, remoteRaw;
    try {
      localHead = (await this._git(["rev-parse", "HEAD"])).stdout.trim();
      remoteRaw = (await this._git(["ls-remote", "origin", "main"])).stdout.trim();
    } catch (e) {
      log.warn("self_update_check_failed", { error: describeErr(e) });
      return { action: "check_failed" };
    }

    const remoteHead = (remoteRaw.split(/\s+/)[0] || "").trim();
    if (!remoteHead) {
      log.warn("self_update_check_failed", { error: "empty ls-remote origin main response" });
      return { action: "check_failed" };
    }

    if (remoteHead === localHead) {
      // Debug-suppressed: this fires on every check (default every 10
      // seconds) and "nothing to do" is not worth an operator's attention.
      log.debug("self_update_up_to_date", { head: shortSha(localHead) });
      return { action: "up_to_date" };
    }

    log.info("self_update_available", { local: shortSha(localHead), remote: shortSha(remoteHead) });

    // Decide npm install BEFORE pulling, from the range about to be pulled,
    // so a diff failure just means "skip the diff optimization" rather than
    // risking installing (or skipping) against the wrong tree state.
    let lockChanged = false;
    try {
      const diff = await this._git(["diff", "--name-only", `${localHead}..${remoteHead}`]);
      lockChanged = diff.stdout
        .split("\n")
        .map((l) => l.trim())
        .some((f) => LOCKFILE_PATHS.has(f));
    } catch (e) {
      log.warn("self_update_diff_failed", { error: describeErr(e) });
      lockChanged = true; // can't prove it's safe to skip - install to be safe
    }

    log.info("self_update_pulling", {});
    try {
      await this._git(["pull", "--ff-only"]);
    } catch (e) {
      // Dirty tree / diverged history / network blip - never leave the
      // checkout worse off than we found it, and never retry-loop hot: the
      // next scheduled check (default 10s) will simply try again.
      log.warn("self_update_pull_failed", { error: describeErr(e) });
      return { action: "pull_failed" };
    }

    let npmInstalled = false;
    if (lockChanged) {
      log.info("self_update_npm_install", {});
      try {
        await this.runner("npm", ["install", "--no-audit", "--no-fund"], { cwd: this.brainDir });
        npmInstalled = true;
      } catch (e) {
        // Code is already pulled at this point; log loudly but still
        // restart - a stale/missing dependency will surface clearly on the
        // next boot's logs rather than silently running old code forever.
        log.warn("self_update_npm_install_failed", { error: describeErr(e) });
      }
    }

    log.info("self_update_restarting", {});
    const { capped } = await this._waitForIdleWithCap();
    if (capped) {
      log.warn("self_update_restarting_with_turn_in_flight", { idleWaitCapSec: this.idleWaitCapSec });
    }
    await this.restart();
    return { action: "restarted", npmInstalled, idleCapped: capped };
  }
}

function shortSha(sha) {
  return typeof sha === "string" ? sha.slice(0, 10) : String(sha);
}

function describeErr(e) {
  if (!e) return "unknown error";
  const stderr = String(e.stderr || "").trim();
  return stderr || String(e.message || e);
}
