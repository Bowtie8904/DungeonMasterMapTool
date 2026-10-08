"use strict";

/**
 * One shared poller per application address (plan.md 3.36.3).
 *
 * Every visible key registers the control ids it needs; the poller then issues a single
 * `GET /api/controls?ids=...` for their union, plus at most one `GET /api/state` when a map key is visible.
 * It never overlaps requests, stops completely once the last key disappears, and backs off on errors so a
 * closed application cannot cause a request storm.
 */

const api = require("./api");

const BACKOFF_MS = [1000, 2000, 5000, 10000, 20000];
const MIN_INTERVAL_MS = 250;
const DEFAULT_INTERVAL_MS = 1000;
const MAP_NAME_TTL_MS = 60000;

class Poller {
	constructor(address, log) {
		this.address = address;
		this.log = log;
		this.watchers = new Map();
		this.intervalMs = DEFAULT_INTERVAL_MS;
		this.timer = null;
		this.running = false;
		this.failures = 0;
		this.nextDueAt = 0;
		this.last = null;
		this.maps = null;
		this.mapsAt = 0;
	}

	setInterval(ms) {
		const value = Math.max(MIN_INTERVAL_MS, Number(ms) || DEFAULT_INTERVAL_MS);
		if (value === this.intervalMs) {
			return;
		}
		this.intervalMs = value;
		this.#schedule(0);
	}

	/** Registers or updates a key. `watcher` is `{ ids, wantsState, onUpdate }`. */
	watch(context, watcher) {
		const known = this.watchers.has(context);
		this.watchers.set(context, watcher);
		// A new or retargeted key should light up immediately instead of waiting for the next tick.
		this.#schedule(known ? 0 : 0);
	}

	unwatch(context) {
		this.watchers.delete(context);
		if (this.watchers.size === 0 && this.timer) {
			clearTimeout(this.timer);
			this.timer = null;
		}
	}

	get idle() {
		return this.watchers.size === 0;
	}

	/** Pulls the next poll forward, used right after a key press to pick up side effects. */
	refreshSoon(delayMs = 250) {
		this.#schedule(delayMs);
	}

	#schedule(delayMs) {
		if (this.watchers.size === 0 || this.running) {
			return;
		}
		const dueAt = Date.now() + delayMs;
		if (this.timer && this.nextDueAt <= dueAt) {
			return;
		}
		if (this.timer) {
			clearTimeout(this.timer);
		}
		this.nextDueAt = dueAt;
		this.timer = setTimeout(() => this.#tick(), delayMs);
		if (this.timer.unref) {
			this.timer.unref();
		}
	}

	async #tick() {
		this.timer = null;
		if (this.watchers.size === 0) {
			return;
		}
		this.running = true;
		const ids = [];
		let wantsState = false;
		let wantsMaps = false;
		for (const watcher of this.watchers.values()) {
			for (const id of watcher.ids) {
				if (id && !ids.includes(id)) {
					ids.push(id);
				}
			}
			wantsState = wantsState || watcher.wantsState;
			wantsMaps = wantsMaps || watcher.wantsMaps;
		}
		// Map names are only needed by keys that display one, and maps are renamed rarely, so this is a slow
		// refresh rather than part of every tick.
		const staleMaps = this.maps === null || Date.now() - this.mapsAt > MAP_NAME_TTL_MS;
		let snapshot;
		try {
			const requests = [ids.length ? api.describe(this.address, ids.slice(0, 128)) : Promise.resolve([])];
			requests.push(wantsState ? api.state(this.address) : Promise.resolve(null));
			requests.push(wantsMaps && staleMaps ? api.maps(this.address) : Promise.resolve(null));
			const [controls, state, maps] = await Promise.all(requests);
			const byId = new Map();
			for (const control of controls || []) {
				byId.set(control.id, control);
			}
			if (maps) {
				this.maps = new Map(maps.map((map) => [String(map.id).toLowerCase(), map.name]));
				this.mapsAt = Date.now();
			}
			snapshot = { ok: true, controls: byId, state, maps: this.maps, error: null };
			this.failures = 0;
		} catch (error) {
			snapshot = { ok: false, controls: new Map(), state: null, maps: this.maps, error: error.message };
			if (this.failures === 0) {
				this.log(`Polling ${this.address.host}:${this.address.port} failed: ${error.message}`);
			}
			this.failures++;
		}
		this.last = snapshot;
		for (const watcher of [...this.watchers.values()]) {
			try {
				watcher.onUpdate(snapshot);
			} catch (error) {
				this.log("Updating a key failed: " + error.message);
			}
		}
		this.running = false;
		const delay = snapshot.ok
			? this.intervalMs
			: Math.max(this.intervalMs, BACKOFF_MS[Math.min(this.failures - 1, BACKOFF_MS.length - 1)]);
		this.#schedule(delay);
	}
}

/** Keeps one poller per `host:port`, so several applications or ports stay independent. */
class Pollers {
	constructor(log) {
		this.log = log;
		this.byKey = new Map();
		this.intervalMs = DEFAULT_INTERVAL_MS;
	}

	static key(address) {
		return `${address.host}:${address.port}`;
	}

	for(address) {
		const key = Pollers.key(address);
		let poller = this.byKey.get(key);
		if (!poller) {
			poller = new Poller(address, this.log);
			poller.setInterval(this.intervalMs);
			this.byKey.set(key, poller);
		}
		return poller;
	}

	setInterval(ms) {
		this.intervalMs = Math.max(MIN_INTERVAL_MS, Number(ms) || DEFAULT_INTERVAL_MS);
		for (const poller of this.byKey.values()) {
			poller.setInterval(this.intervalMs);
		}
	}

	unwatch(context) {
		for (const [key, poller] of [...this.byKey.entries()]) {
			poller.unwatch(context);
			if (poller.idle) {
				this.byKey.delete(key);
			}
		}
	}
}

module.exports = { Pollers, DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS };
