"use strict";

/**
 * Dungeon Master Map Tool - Stream Deck plugin (plan.md 3.36).
 *
 * Runs in Stream Deck's Node.js runtime, talks to the application's local control API over plain HTTP and keeps
 * every visible key in sync through one shared poller per application address.
 */

const { WebSocketClient } = require("./lib/ws");
const api = require("./lib/api");
const render = require("./lib/render");
const { Pollers, DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS } = require("./lib/poller");
const fs = require("node:fs/promises");
const path = require("node:path");

const ACTION = {
	CONTROL: "com.dmmt.dungeonmaster.control",
	VALUE: "com.dmmt.dungeonmaster.value",
	DROPDOWN: "com.dmmt.dungeonmaster.dropdown",
	MUSIC: "com.dmmt.dungeonmaster.music",
	EFFECT: "com.dmmt.dungeonmaster.effect",
	MAP: "com.dmmt.dungeonmaster.map",
};

const DEFAULT_ADDRESS = { host: "127.0.0.1", port: 7071 };

/** Generic key artwork for Switch Map keys, which have no control and therefore no key image endpoint. */
const MAP_ARTWORK = path.join(__dirname, "imgs", "keys", "map.png");

function log(message) {
	process.stdout.write(`[dmmt] ${message}\n`);
}

// ---------------------------------------------------------------- settings

function address(global) {
	const host = String((global && global.host) || DEFAULT_ADDRESS.host).trim() || DEFAULT_ADDRESS.host;
	const port = Number(global && global.port) || DEFAULT_ADDRESS.port;
	return { host, port };
}

function controlIdOf(settings) {
	return String((settings && settings.controlId) || "").trim();
}

/**
 * The key image source: an explicitly pasted URL wins, otherwise it is derived from the control id. `tag` is the
 * artwork fingerprint reported by discovery, so re-colouring or re-icon-ing a category in the application
 * invalidates the cached artwork on the next poll instead of showing stale art until a restart.
 */
function imageKey(settings, action, addr, tag) {
	const url = String((settings && settings.imageUrl) || "").trim();
	if (url) {
		return { cacheKey: url, url };
	}
	if (action === ACTION.MAP) {
		// Library maps are not controls, so the application has no key image for them: use the generic map
		// artwork shipped with the plugin, composited with the same highlight states as every other key.
		return { cacheKey: "plugin:map", file: MAP_ARTWORK };
	}
	const id = controlIdOf(settings);
	if (!id) {
		return null;
	}
	const operation = settings && settings.mode && settings.mode !== "set" ? settings.mode : null;
	return {
		cacheKey: `${addr.host}:${addr.port}/${id}/${operation || "plain"}/${tag || "?"}`,
		controlId: id,
		operation,
	};
}

/**
 * The amount an increase/decrease (or set) key applies. An empty amount means "use the control's own step", so a
 * volume key moves by the same amount as the application's UI without the user having to know its units. The
 * field is parsed leniently because a localized number input can hand us a comma.
 */
function amountOf(settings, descriptor) {
	const raw = settings && settings.amount !== undefined && settings.amount !== null ? String(settings.amount) : "";
	const text = raw.trim().replace(",", ".");
	if (text !== "") {
		const typed = Number(text);
		if (Number.isFinite(typed)) {
			return typed;
		}
	}
	const step = descriptor && Number(descriptor.step);
	return Number.isFinite(step) && step > 0 ? step : null;
}

/** The caption under the artwork: nothing, the target's name, or its current value. */
function titleOf(settings, name, value) {
	const mode = (settings && settings.title) || "none";
	if (mode === "name") {
		return name || "";
	}
	if (mode === "value") {
		return value === null || value === undefined ? "" : String(value);
	}
	return null;
}

// ---------------------------------------------------------------- evaluation

function formatValue(descriptor) {
	if (!descriptor || descriptor.value === undefined || descriptor.value === null) {
		return "";
	}
	if (typeof descriptor.value === "number") {
		return descriptor.type === "slider" && descriptor.max <= 1
			? Math.round(descriptor.value * 100) + "%"
			: String(Math.round(descriptor.value * 100) / 100);
	}
	return String(descriptor.value);
}

/** Turns a poll snapshot into the key's appearance: `{ state, title }`. */
function evaluate(action, settings, snapshot) {
	if (!snapshot.ok) {
		return { state: "error", title: null, problem: snapshot.error };
	}
	if (action === ACTION.MAP) {
		const mapId = String((settings && settings.mapId) || "").trim();
		if (!mapId) {
			return { state: "error", title: null, problem: "No map selected." };
		}
		const open = snapshot.state || {};
		const level = String((settings && settings.level) !== undefined ? settings.level : "").trim();
		const sameMap = String(open.id || "").toLowerCase() === mapId.toLowerCase();
		const sameLevel = level === "" || Number(level) === Number(open.level);
		const name = (snapshot.maps && snapshot.maps.get(mapId.toLowerCase())) || settings.mapName || "";
		return {
			state: sameMap && sameLevel ? "active" : "inactive",
			title: titleOf(settings, name, name),
			problem: null,
		};
	}
	const id = controlIdOf(settings);
	if (!id) {
		return { state: "error", title: null, problem: "No control selected." };
	}
	const descriptor = snapshot.controls.get(id);
	if (!descriptor) {
		return { state: "error", title: null, problem: `The application does not know "${id}".` };
	}
	const name = descriptor.label || id;
	if (descriptor.disabled) {
		return { state: "disabled", title: titleOf(settings, name, formatValue(descriptor)), problem: null };
	}
	if (action === ACTION.DROPDOWN) {
		const index = Number((settings && settings.index) || 0);
		const choices = descriptor.choices || [];
		return {
			state: descriptor.index === index ? "active" : "inactive",
			title: titleOf(settings, name, choices[index] || ""),
			problem: index >= 0 && index < choices.length ? null : `Option ${index} does not exist.`,
		};
	}
	if (action === ACTION.VALUE) {
		const mode = (settings && settings.mode) || "set";
		const amount = amountOf(settings, descriptor);
		const matches = mode === "set" && amount !== null && Math.abs(Number(descriptor.value) - amount) < 1e-6;
		return {
			state: matches ? "active" : "inactive",
			title: titleOf(settings, name, formatValue(descriptor)),
			problem: null,
		};
	}
	// Buttons have no lasting state; toggles (including music categories and sound effects) report `value`.
	const active = descriptor.type === "toggle" && descriptor.value === true;
	return {
		state: active ? "active" : "inactive",
		title: titleOf(settings, name, formatValue(descriptor)),
		problem: null,
	};
}

function watchedIds(action, settings) {
	if (action === ACTION.MAP) {
		return [];
	}
	const id = controlIdOf(settings);
	return id ? [id] : [];
}

// ---------------------------------------------------------------- plugin

class Plugin {
	constructor(port, uuid, registerEvent) {
		this.uuid = uuid;
		this.registerEvent = registerEvent;
		this.keys = new Map();
		this.global = {};
		this.inspector = null;
		this.artwork = new Map();
		this.pollers = new Pollers(log);
		this.socket = new WebSocketClient(port);
		this.socket.on("open", () => {
			this.socket.send(JSON.stringify({ event: registerEvent, uuid }));
			this.send({ event: "getGlobalSettings", context: uuid });
		});
		this.socket.on("message", (text) => this.#dispatch(text));
		this.socket.on("error", (error) => log("Stream Deck socket error: " + error.message));
		this.socket.on("close", () => process.exit(0));
	}

	send(payload) {
		this.socket.send(JSON.stringify(payload));
	}

	#dispatch(text) {
		let message;
		try {
			message = JSON.parse(text);
		} catch {
			return;
		}
		const handler = {
			didReceiveGlobalSettings: () => this.#globalSettings(message),
			didReceiveSettings: () => this.#settings(message),
			willAppear: () => this.#appear(message),
			willDisappear: () => this.#disappear(message),
			keyUp: () => this.#press(message),
			propertyInspectorDidAppear: () => this.#inspectorAppeared(message),
			propertyInspectorDidDisappear: () => {
				this.inspector = null;
			},
			sendToPlugin: () => this.#fromInspector(message),
			titleParametersDidChange: () => undefined,
		}[message.event];
		if (handler) {
			handler();
		}
	}

	#globalSettings(message) {
		this.global = (message.payload && message.payload.settings) || {};
		this.pollers.setInterval(this.global.pollMs || DEFAULT_INTERVAL_MS);
		for (const context of this.keys.keys()) {
			this.#rewatch(context);
		}
		this.#toInspector({ kind: "global", global: this.#globalForInspector() });
	}

	#globalForInspector() {
		const addr = address(this.global);
		return {
			host: addr.host,
			port: addr.port,
			pollMs: Math.max(MIN_INTERVAL_MS, Number(this.global.pollMs) || DEFAULT_INTERVAL_MS),
			confirmPress: this.global.confirmPress === true,
		};
	}

	#settings(message) {
		const key = this.keys.get(message.context);
		if (!key) {
			return;
		}
		const previous = controlIdOf(key.settings);
		key.settings = (message.payload && message.payload.settings) || {};
		if (controlIdOf(key.settings) !== previous) {
			// A different control has a different fingerprint; keeping the old one would reuse its artwork.
			key.imageTag = null;
		}
		key.appearance = null;
		this.#rewatch(message.context);
	}

	#appear(message) {
		this.keys.set(message.context, {
			action: message.action,
			settings: (message.payload && message.payload.settings) || {},
			appearance: null,
			title: null,
		});
		this.#rewatch(message.context);
	}

	#disappear(message) {
		this.keys.delete(message.context);
		this.pollers.unwatch(message.context);
	}

	/** (Re)registers a key with the poller of its address and refreshes its artwork. */
	#rewatch(context) {
		const key = this.keys.get(context);
		if (!key) {
			return;
		}
		const addr = address(this.global);
		this.pollers.unwatch(context);
		this.pollers.for(addr).watch(context, {
			ids: watchedIds(key.action, key.settings),
			wantsState: key.action === ACTION.MAP,
			wantsMaps: key.action === ACTION.MAP && key.settings && key.settings.title === "name",
			onUpdate: (snapshot) => this.#update(context, snapshot),
		});
		this.#loadArtwork(context);
	}

	async #loadArtwork(context) {
		const key = this.keys.get(context);
		if (!key) {
			return;
		}
		const addr = address(this.global);
		const source = imageKey(key.settings, key.action, addr, key.imageTag);
		// Control artwork waits for the first poll, which carries the fingerprint; fetching before that would
		// cache the image under a placeholder key that later edits could not invalidate.
		if (source && source.controlId && !key.imageTag) {
			return;
		}
		const cacheKey = source ? source.cacheKey : "";
		if (key.artworkKey === cacheKey) {
			// Already loaded, or a recent failure: retry at most every 30 seconds instead of every poll.
			if (key.artwork || Date.now() - (key.artworkFailedAt || 0) < 30000) {
				return;
			}
		}
		key.artworkKey = cacheKey;
		key.artwork = null;
		key.appearance = null;
		if (!source) {
			return;
		}
		if (this.artwork.has(cacheKey)) {
			key.artwork = this.artwork.get(cacheKey);
			this.#redraw(context);
			return;
		}
		key.artworkFailedAt = Date.now();
		try {
			const bytes = source.file
				? await fs.readFile(source.file)
				: source.url
					? await api.fetchUrl(source.url)
					: await api.keyImage(addr, source.controlId, source.operation);
			const decoded = render.parse(bytes);
			this.artwork.set(cacheKey, decoded);
			const current = this.keys.get(context);
			if (current && current.artworkKey === cacheKey) {
				current.artwork = decoded;
				current.artworkFailedAt = 0;
				this.#redraw(context);
			}
		} catch (error) {
			log(`Could not load the key image for ${cacheKey}: ${error.message}`);
		}
	}

	/** Re-applies the newest poll result, used once artwork arrives between two polls. */
	#redraw(context) {
		const last = this.pollers.for(address(this.global)).last;
		if (last) {
			this.#update(context, last);
		}
	}

	#update(context, snapshot) {
		const key = this.keys.get(context);
		if (!key) {
			return;
		}
		const result = evaluate(key.action, key.settings, snapshot);
		// The application re-renders a key image when its icon or colour changes, and discovery then reports a new
		// fingerprint. A new fingerprint drops the cached artwork and refetches it exactly once.
		const descriptor = snapshot.controls && snapshot.controls.get(controlIdOf(key.settings));
		const tag = descriptor && descriptor.image ? String(descriptor.image) : null;
		if (tag && key.imageTag !== tag) {
			key.imageTag = tag;
			key.artworkFailedAt = 0;
			this.#loadArtwork(context);
		}
		const appearance = `${result.state}|${key.artworkKey || ""}|${key.artwork ? "art" : "flat"}`;
		if (key.appearance !== appearance) {
			key.appearance = appearance;
			this.send({
				event: "setImage",
				context,
				payload: { image: "data:image/png;base64," + render.render(key.artwork, result.state), target: 0 },
			});
		}
		// `null` means this key does not manage its title, so the one typed in Stream Deck survives. An empty
		// string is a managed title with nothing to show and does clear the key.
		if (result.title !== null && result.title !== undefined && key.title !== result.title) {
			key.title = result.title;
			this.send({ event: "setTitle", context, payload: { title: result.title, target: 0 } });
		} else if (result.title === null && key.title !== null && key.title !== undefined) {
			// Switched back to "No title": hand the key back to Stream Deck's own title.
			key.title = null;
			this.send({ event: "setTitle", context, payload: { target: 0 } });
		}
		if (result.problem && key.problem !== result.problem) {
			log(`Key ${key.action}: ${result.problem}`);
		}
		key.problem = result.problem || null;
	}

	async #press(message) {
		const key = this.keys.get(message.context);
		if (!key) {
			return;
		}
		key.settings = (message.payload && message.payload.settings) || key.settings;
		const addr = address(this.global);
		try {
			const result = await this.#command(key, addr);
			if (this.global.confirmPress) {
				this.send({ event: "showOk", context: message.context });
			}
			if (key.action !== ACTION.MAP && result && result.id && result.type) {
				// Apply the command's own answer at once; the follow-up poll catches side effects such as
				// another music category stopping.
				this.#update(message.context, { ok: true, controls: new Map([[result.id, result]]), state: null });
			}
		} catch (error) {
			log(`Command failed: ${error.message}`);
			this.send({ event: "showAlert", context: message.context });
		}
		this.pollers.for(addr).refreshSoon(250);
	}

	#command(key, addr) {
		const settings = key.settings || {};
		if (key.action === ACTION.MAP) {
			const mapId = String(settings.mapId || "").trim();
			if (!mapId) {
				return Promise.reject(new Error("No map selected."));
			}
			return api.switchMap(addr, mapId, String(settings.level === undefined ? "" : settings.level).trim());
		}
		const id = controlIdOf(settings);
		if (!id) {
			return Promise.reject(new Error("No control selected."));
		}
		if (key.action === ACTION.DROPDOWN) {
			return api.execute(addr, id, { index: Number(settings.index) || 0 });
		}
		if (key.action === ACTION.VALUE) {
			const mode = settings.mode || "set";
			const descriptor = (this.pollers.for(addr).last || { controls: new Map() }).controls.get(id);
			const amount = amountOf(settings, descriptor);
			if (amount === null) {
				return Promise.reject(new Error("Enter an amount: the control reports no step of its own."));
			}
			return api.execute(addr, id, mode === "set" ? { value: amount } : { [mode]: Math.abs(amount) });
		}
		return api.execute(addr, id, {});
	}

	// ------------------------------------------------------------ inspector

	#inspectorAppeared(message) {
		this.inspector = message.context;
		this.#toInspector({ kind: "global", global: this.#globalForInspector() });
	}

	#toInspector(payload) {
		if (this.inspector) {
			this.send({ event: "sendToPropertyInspector", context: this.inspector, payload });
		}
	}

	async #fromInspector(message) {
		const payload = message.payload || {};
		if (payload.kind === "setGlobal") {
			this.global = {
				host: String(payload.host || DEFAULT_ADDRESS.host).trim() || DEFAULT_ADDRESS.host,
				port: Number(payload.port) || DEFAULT_ADDRESS.port,
				pollMs: Math.max(MIN_INTERVAL_MS, Number(payload.pollMs) || DEFAULT_INTERVAL_MS),
				confirmPress: payload.confirmPress === true,
			};
			this.send({ event: "setGlobalSettings", context: this.uuid, payload: this.global });
			this.pollers.setInterval(this.global.pollMs);
			this.artwork.clear();
			for (const [context, key] of this.keys) {
				key.artworkKey = undefined;
				key.artwork = undefined;
				this.#rewatch(context);
			}
			this.#toInspector({ kind: "global", global: this.#globalForInspector() });
			return;
		}
		if (payload.kind === "listControls") {
			const addr = address(this.global);
			try {
				const controls = await api.describe(addr, null);
				this.#toInspector({
					kind: "controls",
					controls: controls.map((control) => ({
						id: control.id,
						label: control.label,
						tooltip: control.tooltip,
						type: control.type,
						choices: control.choices || null,
					})),
				});
			} catch (error) {
				this.#toInspector({ kind: "controls", controls: null, error: error.message });
			}
			return;
		}
		if (payload.kind === "listMaps") {
			const addr = address(this.global);
			try {
				const maps = await api.maps(addr);
				this.#toInspector({
					kind: "maps",
					maps: maps.map((map) => ({ id: map.id, name: map.name, multilevel: map.multilevel })),
				});
			} catch (error) {
				this.#toInspector({ kind: "maps", maps: null, error: error.message });
			}
			return;
		}
		if (payload.kind === "parseUrl") {
			const parsed = api.parseCopiedUrl(payload.url);
			this.#toInspector({ kind: "parsedUrl", parsed });
			return;
		}
		if (payload.kind === "refresh") {
			this.artwork.clear();
			for (const [context, key] of this.keys) {
				key.artworkKey = undefined;
				key.artwork = undefined;
				this.#rewatch(context);
			}
		}
	}
}

function main() {
	const args = process.argv.slice(2);
	const value = (name) => {
		const index = args.indexOf(name);
		return index >= 0 && index + 1 < args.length ? args[index + 1] : null;
	};
	const port = Number(value("-port"));
	const uuid = value("-pluginUUID");
	const registerEvent = value("-registerEvent");
	if (!port || !uuid || !registerEvent) {
		log("Missing Stream Deck registration arguments; the plugin can only be started by Stream Deck.");
		process.exit(1);
	}
	new Plugin(port, uuid, registerEvent);
}

if (require.main === module) {
	main();
}

module.exports = { evaluate, formatValue, watchedIds, imageKey, amountOf, titleOf, ACTION };
