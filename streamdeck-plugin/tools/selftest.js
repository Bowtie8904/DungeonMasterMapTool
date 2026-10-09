"use strict";

/**
 * Self-test for the plugin's pure logic: PNG round-trip, key rendering, URL parsing, state evaluation and the
 * shared poller (batching, error backoff, stopping when the last key disappears).
 *
 * Run with: node tools/selftest.js
 */

const assert = require("node:assert");
const http = require("node:http");
const path = require("node:path");
const fsSync = require("node:fs");
const vm = require("node:vm");

const base = path.join(__dirname, "..", "com.dmmt.dungeonmaster.sdPlugin");
const png = require(path.join(base, "lib/png"));
const render = require(path.join(base, "lib/render"));
const api = require(path.join(base, "lib/api"));
const { Pollers } = require(path.join(base, "lib/poller"));
const plugin = require(path.join(base, "plugin"));

let failures = 0;
async function test(name, body) {
	try {
		await body();
		process.stdout.write(`  ok   ${name}\n`);
	} catch (error) {
		failures++;
		process.stdout.write(`  FAIL ${name}\n       ${error.message}\n`);
	}
}

function sample(width, height) {
	const pixels = Buffer.alloc(width * height * 4);
	for (let i = 0; i < width * height; i++) {
		pixels[i * 4] = i % 256;
		pixels[i * 4 + 1] = (i * 7) % 256;
		pixels[i * 4 + 2] = (i * 13) % 256;
		pixels[i * 4 + 3] = 255;
	}
	return { width, height, pixels };
}

async function main() {
	process.stdout.write("Dungeon Master Map Tool Stream Deck plugin self-test\n");

	await test("control picker uses descriptions or ids, never short titles", () => {
		function select() {
			return {
				options: [],
				value: "",
				set innerHTML(value) { this.options = []; },
				appendChild(option) { this.options.push(option); },
			};
		}
		const elements = Object.fromEntries([
			"section", "control", "sectionItem", "controlItem", "controlIdItem", "mapItem", "mapIdItem", "levelItem",
			"modeItem", "amountItem", "colorItem", "indexItem", "titleItem", "title", "pasteHint", "color", "mode",
		].map((id) => [id, select()]));
		for (const element of Object.values(elements)) {
			element.classList = { toggle(name, hidden) { if (name === "hidden") element.hidden = hidden; } };
			element.querySelector = () => ({ textContent: "" });
		}
		const context = vm.createContext({
			document: { getElementById: (id) => elements[id] },
			Option: function(text, value) { this.text = text; this.value = value; },
		});
		vm.runInContext(fsSync.readFileSync(path.join(base, "pi/inspector.js"), "utf8"), context);
		vm.runInContext(`
			action = ACTION.CONTROL;
			settings = { section: "audio", controlId: "audio.musicPlay" };
			controls = [
				{ id: "audio.play", type: "button", label: "Play/pause", tooltip: "Pause or resume music and sound effects" },
				{ id: "audio.musicPlay", type: "button", label: "Play/pause", tooltip: "Play or pause music only" },
				{ id: "audio.effectsPause", type: "toggle", label: "Play/pause", tooltip: "Pause or resume sound effects only" },
				{ id: "audio.legacy", type: "button", label: "Older API name" },
				{ id: "audio.noDescription", type: "button", label: "Empty description", tooltip: "" },
			];
			fillControls();
		`, context);
		assert.deepStrictEqual(elements.control.options.slice(1).map((option) => option.text), [
			"Pause or resume music and sound effects",
			"Play or pause music only",
			"Pause or resume sound effects only",
			"audio.legacy",
			"audio.noDescription",
		]);
		assert.strictEqual(elements.control.value, "audio.musicPlay");
		assert.strictEqual(elements.control.options[2].value, "audio.musicPlay");
		assert.strictEqual(
			vm.runInContext("SCOPE[ACTION.VALUE].types.includes('color')", context),
			true,
			"the value action must include color picker controls",
		);
		vm.runInContext(`
			action = ACTION.VALUE;
			settings = { section: "effects", controlId: "effects.color" };
			controls = [{ id: "effects.color", type: "color", value: "#FF0000FF", tooltip: "Effect colour" }];
			fillControls();
		`, context);
		assert.strictEqual(elements.colorItem.hidden, false);
		assert.strictEqual(elements.modeItem.hidden, true);
		assert.strictEqual(elements.amountItem.hidden, true);
	});

	await test("plugin forwards API tooltip descriptions to the property inspector", async () => {
		const sent = [];
		let socket;
		class FakeSocket extends require("node:events").EventEmitter {
			constructor() {
				super();
				socket = this;
			}
			send(text) { sent.push(JSON.parse(text)); }
		}
		const descriptors = [
			{ id: "audio.play", type: "button", label: "Play/pause", tooltip: "Pause or resume music and sound effects" },
			{ id: "audio.musicPlay", type: "button", label: "Play/pause", tooltip: "Play or pause music only" },
			{ id: "audio.effectsPause", type: "toggle", label: "Play/pause", tooltip: "Pause or resume sound effects only" },
		];
		const context = vm.createContext({
			require: (name) => {
				if (name === "./lib/ws") return { WebSocketClient: FakeSocket };
				if (name === "./lib/api") return { describe: async () => descriptors };
				if (name.startsWith("./")) return require(path.join(base, name));
				return require(name);
			},
			module: { exports: {} },
			__dirname: base,
			process,
		});
		vm.runInContext(fsSync.readFileSync(path.join(base, "plugin.js"), "utf8") +
			"\nnew Plugin(1234, 'test-plugin', 'registerPlugin');", context);
		socket.emit("message", JSON.stringify({ event: "propertyInspectorDidAppear", context: "inspector" }));
		socket.emit("message", JSON.stringify({ event: "sendToPlugin", payload: { kind: "listControls" } }));
		await new Promise((resolve) => setImmediate(resolve));
		const response = sent.find((message) => message.payload && message.payload.kind === "controls");
		assert.ok(response, "the plugin must reply with the control list");
		assert.strictEqual(response.context, "inspector");
		for (const [index, descriptor] of descriptors.entries()) {
			assert.strictEqual(response.payload.controls[index].tooltip, descriptor.tooltip);
			assert.strictEqual(response.payload.controls[index].label, descriptor.label);
			assert.strictEqual(response.payload.controls[index].id, descriptor.id);
		}
	});

	await test("PNG encodes and decodes losslessly", () => {
		const image = sample(144, 144);
		const decoded = png.decode(png.encode(image));
		assert.strictEqual(decoded.width, 144);
		assert.strictEqual(decoded.height, 144);
		assert.ok(decoded.pixels.equals(image.pixels));
	});

	await test("PNG decodes every filter type", () => {
		const zlib = require("node:zlib");
		const width = 4;
		const height = 5;
		const raw = Buffer.alloc((width * 4 + 1) * height);
		for (let y = 0; y < height; y++) {
			raw[y * (width * 4 + 1)] = y; // filter types 0..4
			for (let x = 1; x <= width * 4; x++) {
				raw[y * (width * 4 + 1) + x] = (x * (y + 3)) % 256;
			}
		}
		const header = Buffer.alloc(13);
		header.writeUInt32BE(width, 0);
		header.writeUInt32BE(height, 4);
		header[8] = 8;
		header[9] = 6;
		const chunks = [Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])];
		for (const [type, body] of [
			["IHDR", header],
			["IDAT", zlib.deflateSync(raw)],
			["IEND", Buffer.alloc(0)],
		]) {
			const length = Buffer.alloc(4);
			length.writeUInt32BE(body.length, 0);
			// CRCs are not validated by the decoder, so a zero checksum is enough to exercise the filters.
			chunks.push(length, Buffer.from(type), body, Buffer.alloc(4));
		}
		const decoded = png.decode(Buffer.concat(chunks));
		assert.strictEqual(decoded.pixels.length, width * height * 4);
	});

	await test("rendering produces a different image per state", () => {
		const artwork = sample(144, 144);
		const images = ["active", "inactive", "disabled", "error"].map((state) => render.render(artwork, state));
		assert.strictEqual(new Set(images).size, 4);
		for (const image of images) {
			const decoded = png.decode(Buffer.from(image, "base64"));
			assert.strictEqual(decoded.width, 144);
		}
	});

	await test("rendering works without artwork", () => {
		const decoded = png.decode(Buffer.from(render.render(null, "error"), "base64"));
		assert.strictEqual(decoded.width, render.SIZE);
		// The red frame must actually be written into the corner pixels.
		assert.ok(decoded.pixels[0] > 150 && decoded.pixels[1] < 120);
	});

	await test("color swatches show the exact configured color in every state", () => {
		for (const state of ["active", "inactive", "disabled", "error"]) {
			const decoded = png.decode(Buffer.from(render.renderColor("#12AB34", state), "base64"));
			assert.strictEqual(decoded.width, 144);
			assert.deepStrictEqual([...decoded.pixels.slice((72 * 144 + 72) * 4, (72 * 144 + 72) * 4 + 4)], [
				0x12, 0xab, 0x34, 0xff,
			]);
		}
	});

	await test("copied URLs are understood", () => {
		const control = api.parseCopiedUrl("http://192.168.1.50:7071/api/controls/lighting/night");
		assert.deepStrictEqual(control, {
			kind: "control",
			address: { host: "192.168.1.50", port: 7071 },
			controlId: "lighting.night",
			query: {},
		});
		const image = api.parseCopiedUrl(
			"http://127.0.0.1:7071/api/controls/audio/category/8f1c6d94-2b77-4f0e-9a3b-6c5a1d2e7f10/image",
		);
		assert.strictEqual(image.kind, "image");
		assert.strictEqual(image.controlId, "audio.category.8f1c6d94-2b77-4f0e-9a3b-6c5a1d2e7f10");
		const increment = api.parseCopiedUrl("http://127.0.0.1:7071/api/controls/effects/opacity?increment=0.1");
		assert.strictEqual(increment.query.increment, "0.1");
		const color = api.parseCopiedUrl("http://127.0.0.1:7071/api/controls/effects/color?value=%23FF0000");
		assert.strictEqual(color.query.value, "#FF0000");
		const map = api.parseCopiedUrl("http://127.0.0.1:7071/api/maps/abc/switch?level=2");
		assert.strictEqual(map.kind, "map");
		assert.strictEqual(map.mapId, "abc");
		assert.strictEqual(map.query.level, "2");
		assert.strictEqual(api.parseCopiedUrl("not a url"), null);
		assert.strictEqual(api.parseCopiedUrl("http://127.0.0.1:7071/other"), null);
	});

	await test("music categories highlight only while they play", () => {
		const settings = { controlId: "audio.category.combat" };
		const snapshot = (value) => ({
			ok: true,
			controls: new Map([["audio.category.combat", { id: "audio.category.combat", type: "toggle", value }]]),
			state: null,
		});
		assert.strictEqual(plugin.evaluate(plugin.ACTION.MUSIC, settings, snapshot(true)).state, "active");
		assert.strictEqual(plugin.evaluate(plugin.ACTION.MUSIC, settings, snapshot(false)).state, "inactive");
		const missing = plugin.evaluate(plugin.ACTION.MUSIC, settings, { ok: true, controls: new Map(), state: null });
		assert.strictEqual(missing.state, "error");
		const offline = plugin.evaluate(plugin.ACTION.MUSIC, settings, { ok: false, controls: new Map(), error: "x" });
		assert.strictEqual(offline.state, "error");
	});

	await test("disabled, dropdown, value and map keys evaluate correctly", () => {
		const disabled = plugin.evaluate(
			plugin.ACTION.CONTROL,
			{ controlId: "fog.enabled" },
			{ ok: true, controls: new Map([["fog.enabled", { id: "fog.enabled", type: "toggle", disabled: true }]]) },
		);
		assert.strictEqual(disabled.state, "disabled");

		const dropdown = plugin.evaluate(
			plugin.ACTION.DROPDOWN,
			{ controlId: "weather.type", index: 1, title: "value" },
			{
				ok: true,
				controls: new Map([
					["weather.type", { id: "weather.type", type: "dropdown", index: 1, choices: ["None", "Rain"] }],
				]),
			},
		);
		assert.strictEqual(dropdown.state, "active");
		assert.strictEqual(dropdown.title, "Rain");

		const value = plugin.evaluate(
			plugin.ACTION.VALUE,
			{ controlId: "audio.musicVolume", mode: "set", amount: 0.7, title: "value" },
			{
				ok: true,
				controls: new Map([
					["audio.musicVolume", { id: "audio.musicVolume", type: "slider", value: 0.7, min: 0, max: 1 }],
				]),
			},
		);
		assert.strictEqual(value.state, "active");
		assert.strictEqual(value.title, "70%");

		const map = plugin.evaluate(
			plugin.ACTION.MAP,
			{ mapId: "ABC", level: "1" },
			{ ok: true, controls: new Map(), state: { id: "abc", level: 1 } },
		);
		assert.strictEqual(map.state, "active");
		const wrongLevel = plugin.evaluate(
			plugin.ACTION.MAP,
			{ mapId: "abc", level: "0" },
			{ ok: true, controls: new Map(), state: { id: "abc", level: 1 } },
		);
		assert.strictEqual(wrongLevel.state, "inactive");
	});

	await test("an empty amount falls back to the control's own step", () => {
		const slider = { id: "audio.musicVolume", type: "slider", value: 0.5, min: 0, max: 1, step: 0.1 };
		// The regression: a fresh key never had an amount, so the command was rejected and the key alerted.
		assert.strictEqual(plugin.amountOf({}, slider), 0.1);
		assert.strictEqual(plugin.amountOf({ amount: "" }, slider), 0.1);
		assert.strictEqual(plugin.amountOf({ amount: "0.25" }, slider), 0.25);
		assert.strictEqual(plugin.amountOf({ amount: 0.25 }, slider), 0.25);
		// A localized number field can hand us a comma.
		assert.strictEqual(plugin.amountOf({ amount: "0,25" }, slider), 0.25);
		assert.strictEqual(plugin.amountOf({ amount: "nonsense" }, slider), 0.1);
		// Without a step and without an amount there is nothing sensible to send.
		assert.strictEqual(plugin.amountOf({}, { type: "slider" }), null);
		assert.strictEqual(plugin.amountOf({}, undefined), null);
	});

	await test("color value keys set and highlight the configured RGB color", () => {
		const descriptor = { id: "effects.color", type: "color", value: "#FF0000FF", label: "Effect colour" };
		const snapshot = { ok: true, controls: new Map([["effects.color", descriptor]]), state: null };
		const matching = plugin.evaluate(
			plugin.ACTION.VALUE,
			{ controlId: "effects.color", color: "#ff0000" },
			snapshot,
		);
		assert.strictEqual(matching.state, "active");
		assert.strictEqual(plugin.formatValue(descriptor), "#FF0000");
		const different = plugin.evaluate(
			plugin.ACTION.VALUE,
			{ controlId: "effects.color", color: "#00FF00" },
			snapshot,
		);
		assert.strictEqual(different.state, "inactive");
		assert.strictEqual(plugin.colorValue("#AABBCC80"), "#AABBCC");
		assert.strictEqual(plugin.colorValue("red"), null);
	});

	await test("pressing a color value key sends its fixed color", async () => {
		const commands = [];
		let socket;
		class FakeSocket extends require("node:events").EventEmitter {
			constructor() {
				super();
				socket = this;
			}
			send() {}
		}
		class FakePollers {
			constructor() {
				this.poller = {
					last: {
						controls: new Map([
							["effects.color", { id: "effects.color", type: "color", value: "#000000FF" }],
						]),
					},
					watch() {},
					unwatch() {},
					refreshSoon() {},
				};
			}
			for() { return this.poller; }
			setInterval() {}
			unwatch() {}
		}
		const context = vm.createContext({
			require: (name) => {
				if (name === "./lib/ws") return { WebSocketClient: FakeSocket };
				if (name === "./lib/poller") return { Pollers: FakePollers, DEFAULT_INTERVAL_MS: 1000, MIN_INTERVAL_MS: 250 };
				if (name === "./lib/api") return {
					execute: async (...args) => {
						commands.push(args);
						return { id: "effects.color", type: "color", value: "#12AB34FF" };
					},
				};
				if (name.startsWith("./")) return require(path.join(base, name));
				return require(name);
			},
			module: { exports: {} },
			__dirname: base,
			process,
		});
		vm.runInContext(fsSync.readFileSync(path.join(base, "plugin.js"), "utf8") +
			"\nglobalThis.instance = new Plugin(1234, 'test-plugin', 'registerPlugin');", context);
		socket.emit("message", JSON.stringify({
			event: "willAppear",
			context: "color-key",
			action: plugin.ACTION.VALUE,
			payload: { settings: { controlId: "effects.color", mode: "increment", amount: "1", color: "#12AB34" } },
		}));
		socket.emit("message", JSON.stringify({ event: "keyUp", context: "color-key" }));
		await new Promise((resolve) => setImmediate(resolve));
		assert.strictEqual(JSON.stringify(commands), JSON.stringify([[
			{ host: "127.0.0.1", port: 7071 },
			"effects.color",
			{ value: "#12AB34" },
		]]));
	});

	await test("every action can caption its key with the target's name", () => {
		const controls = new Map([
			["audio.category.combat", { id: "audio.category.combat", type: "toggle", value: true, label: "Combat" }],
			["audio.musicVolume", { id: "audio.musicVolume", type: "slider", value: 0.7, max: 1, label: "Music" }],
			["audio.play", { id: "audio.play", type: "button", label: "Play/pause", tooltip: "Pause or resume music and sound effects" }],
		]);
		const named = (action, settings) => plugin.evaluate(action, settings, { ok: true, controls, state: null }).title;
		assert.strictEqual(named(plugin.ACTION.CONTROL, { controlId: "audio.play", title: "name" }), "Play/pause");
		assert.strictEqual(named(plugin.ACTION.MUSIC, { controlId: "audio.category.combat", title: "name" }), "Combat");
		assert.strictEqual(named(plugin.ACTION.VALUE, { controlId: "audio.musicVolume", title: "name" }), "Music");
		assert.strictEqual(named(plugin.ACTION.VALUE, { controlId: "audio.musicVolume", title: "value" }), "70%");
		// No title means the key does not manage its caption, so a hand-typed title survives.
		assert.strictEqual(named(plugin.ACTION.MUSIC, { controlId: "audio.category.combat" }), null);

		const mapKey = plugin.evaluate(
			plugin.ACTION.MAP,
			{ mapId: "ABC", title: "name" },
			{ ok: true, controls: new Map(), state: { id: "abc", level: -1 }, maps: new Map([["abc", "Tavern"]]) },
		);
		assert.strictEqual(mapKey.title, "Tavern");
		// Before the map list arrives the name captured when the map was picked is used.
		const early = plugin.evaluate(
			plugin.ACTION.MAP,
			{ mapId: "abc", title: "name", mapName: "Tavern" },
			{ ok: true, controls: new Map(), state: { id: "abc", level: -1 }, maps: null },
		);
		assert.strictEqual(early.title, "Tavern");
	});

	await test("key image source follows the control and the increment mode", () => {
		const addr = { host: "127.0.0.1", port: 7071 };
		assert.strictEqual(plugin.imageKey({ controlId: "fog.enabled" }, plugin.ACTION.CONTROL, addr).operation, null);
		assert.strictEqual(
			plugin.imageKey({ controlId: "effects.opacity", mode: "increment" }, plugin.ACTION.VALUE, addr).operation,
			"increment",
		);
		const pasted = plugin.imageKey({ controlId: "a.b", imageUrl: "http://h/x" }, plugin.ACTION.CONTROL, addr);
		assert.strictEqual(pasted.url, "http://h/x");
		assert.strictEqual(plugin.imageKey({}, plugin.ACTION.CONTROL, addr), null);
	});

	await test("re-colouring a category in the application invalidates the cached artwork", () => {
		const addr = { host: "127.0.0.1", port: 7071 };
		const settings = { controlId: "audio.category.combat" };
		const before = plugin.imageKey(settings, plugin.ACTION.MUSIC, addr, "1a2b");
		const after = plugin.imageKey(settings, plugin.ACTION.MUSIC, addr, "9f8e");
		assert.notStrictEqual(before.cacheKey, after.cacheKey, "a new fingerprint must miss the artwork cache");
		assert.strictEqual(after.controlId, "audio.category.combat");
		assert.strictEqual(
			plugin.imageKey(settings, plugin.ACTION.MUSIC, addr, "1a2b").cacheKey,
			before.cacheKey,
			"an unchanged fingerprint must keep hitting the cache",
		);
		// A pasted URL is the user's own artwork and must not be invalidated by application edits.
		const pastedA = plugin.imageKey({ imageUrl: "http://h/x" }, plugin.ACTION.MUSIC, addr, "1a2b");
		const pastedB = plugin.imageKey({ imageUrl: "http://h/x" }, plugin.ACTION.MUSIC, addr, "9f8e");
		assert.strictEqual(pastedA.cacheKey, pastedB.cacheKey);
		// A map key has no control image endpoint, so it falls back to the generic artwork shipped with the plugin.
		const mapKey = plugin.imageKey({}, plugin.ACTION.MAP, addr);
		assert.ok(mapKey && mapKey.file, "a map key must fall back to the bundled map artwork");
		assert.ok(fsSync.existsSync(mapKey.file), `missing artwork: ${mapKey.file}`);
		assert.ok(render.parse(fsSync.readFileSync(mapKey.file)).width > 0, "the bundled artwork must decode");
		// A pasted URL still wins over the generic artwork.
		assert.strictEqual(plugin.imageKey({ imageUrl: "http://h/x" }, plugin.ACTION.MAP, addr).url, "http://h/x");
	});

	// ---- the poller against a real local HTTP server -------------------------------------------------

	const requests = [];
	let failRequests = false;
	const server = http.createServer((request, response) => {
		requests.push(request.url);
		if (failRequests) {
			response.writeHead(503, { "Content-Type": "application/json" });
			response.end(JSON.stringify({ status: 503, error: "busy" }));
			return;
		}
		const url = new URL(request.url, "http://127.0.0.1");
		if (url.pathname === "/api/controls") {
			const ids = (url.searchParams.get("ids") || "").split(",").filter(Boolean);
			response.writeHead(200, { "Content-Type": "application/json" });
			response.end(JSON.stringify(ids.map((id) => ({ id, type: "toggle", value: id.endsWith("combat") }))));
			return;
		}
		if (url.pathname === "/api/state") {
			response.writeHead(200, { "Content-Type": "application/json" });
			response.end(JSON.stringify({ busy: false, map: "Keep", level: -1, frozen: false, id: "map-1" }));
			return;
		}
		if (url.pathname === "/api/maps") {
			response.writeHead(200, { "Content-Type": "application/json" });
			response.end(JSON.stringify([{ id: "map-1", name: "Keep", multilevel: false }]));
			return;
		}
		response.writeHead(404);
		response.end("{}");
	});
	await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
	const addr = { host: "127.0.0.1", port: server.address().port };
	const pollers = new Pollers(() => undefined);
	pollers.setInterval(250);

	await test("one request serves every visible key", async () => {
		requests.length = 0;
		const seen = [];
		const poller = pollers.for(addr);
		poller.watch("a", { ids: ["audio.category.combat"], wantsState: false, onUpdate: (s) => seen.push(["a", s]) });
		poller.watch("b", { ids: ["audio.category.calm"], wantsState: false, onUpdate: (s) => seen.push(["b", s]) });
		await new Promise((resolve) => setTimeout(resolve, 400));
		const controlCalls = requests.filter((url) => url.startsWith("/api/controls"));
		assert.ok(controlCalls.length >= 1, "expected at least one poll");
		assert.ok(controlCalls[0].includes("combat"), controlCalls[0]);
		assert.ok(controlCalls[0].includes("calm"), controlCalls[0]);
		assert.ok(seen.some(([key, s]) => key === "a" && s.controls.get("audio.category.combat").value === true));
		assert.ok(seen.some(([key, s]) => key === "b" && s.controls.get("audio.category.calm").value === false));
		// No /api/state request while no map key is visible.
		assert.strictEqual(requests.filter((url) => url.startsWith("/api/state")).length, 0);
	});

	await test("a map key adds exactly one state request per poll", async () => {
		requests.length = 0;
		pollers.for(addr).watch("m", { ids: [], wantsState: true, onUpdate: () => undefined });
		await new Promise((resolve) => setTimeout(resolve, 400));
		const states = requests.filter((url) => url.startsWith("/api/state")).length;
		const controlsCalls = requests.filter((url) => url.startsWith("/api/controls")).length;
		assert.ok(states >= 1 && Math.abs(states - controlsCalls) <= 1, `${states} state vs ${controlsCalls} controls`);
	});

	await test("map names are fetched once and then reused", async () => {
		requests.length = 0;
		const seen = [];
		pollers.for(addr).watch("n", { ids: [], wantsState: true, wantsMaps: true, onUpdate: (s) => seen.push(s) });
		await new Promise((resolve) => setTimeout(resolve, 700));
		pollers.unwatch("n");
		const mapCalls = requests.filter((url) => url.startsWith("/api/maps")).length;
		const stateCalls = requests.filter((url) => url.startsWith("/api/state")).length;
		assert.strictEqual(mapCalls, 1, `names must not be refetched every tick (${mapCalls} calls)`);
		assert.ok(stateCalls > 1, "state is still polled every tick");
		assert.strictEqual(seen[seen.length - 1].maps.get("map-1"), "Keep");
	});

	await test("polling stops once the last key disappears", async () => {
		for (const context of ["a", "b", "m"]) {
			pollers.unwatch(context);
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
		requests.length = 0;
		await new Promise((resolve) => setTimeout(resolve, 600));
		assert.strictEqual(requests.length, 0, "an idle page must not poll at all");
	});

	await test("errors back off instead of hammering the application", async () => {
		failRequests = true;
		requests.length = 0;
		const poller = pollers.for(addr);
		const errors = [];
		poller.watch("e", { ids: ["fog.enabled"], wantsState: false, onUpdate: (s) => errors.push(s) });
		await new Promise((resolve) => setTimeout(resolve, 1500));
		poller.unwatch("e");
		assert.ok(errors.length >= 1 && errors[0].ok === false, "the key must learn about the failure");
		assert.ok(typeof errors[0].error === "string" && errors[0].error.length > 0);
		// Without backoff a 250 ms interval would produce about 6 requests in 1.5 s.
		assert.ok(requests.length <= 4, `expected backoff, saw ${requests.length} requests`);
		failRequests = false;
	});

	pollers.unwatch("e");
	server.close();
	process.stdout.write(failures === 0 ? "\nAll checks passed.\n" : `\n${failures} check(s) failed.\n`);
	process.exit(failures === 0 ? 0 : 1);
}

main();
