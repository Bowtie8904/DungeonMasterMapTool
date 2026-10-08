"use strict";

/**
 * Property inspector for the Dungeon Master Map Tool actions.
 *
 * The inspector never talks to the application itself: it is a browser view, and the local control API rejects
 * requests that carry browser origin headers. Every lookup is relayed through the plugin process instead.
 */

const ACTION = {
	CONTROL: "com.dmmt.dungeonmaster.control",
	VALUE: "com.dmmt.dungeonmaster.value",
	DROPDOWN: "com.dmmt.dungeonmaster.dropdown",
	MUSIC: "com.dmmt.dungeonmaster.music",
	EFFECT: "com.dmmt.dungeonmaster.effect",
	MAP: "com.dmmt.dungeonmaster.map",
};

/** Which control types each action may target, and which id prefix it expects. */
const SCOPE = {
	[ACTION.CONTROL]: { types: ["toggle", "button"], prefix: null, exclude: ["audio.category.", "audio.effect."] },
	[ACTION.VALUE]: { types: ["slider", "spinner"], prefix: null, exclude: [] },
	[ACTION.DROPDOWN]: { types: ["dropdown"], prefix: null, exclude: [] },
	[ACTION.MUSIC]: { types: ["toggle", "button"], prefix: "audio.category.", exclude: [] },
	[ACTION.EFFECT]: { types: ["toggle", "button"], prefix: "audio.effect.", exclude: [] },
	[ACTION.MAP]: { types: [], prefix: null, exclude: [] },
};

let websocket = null;
let uuid = null;
let action = null;
let settings = {};
let controls = null;
let maps = null;

const $ = (id) => document.getElementById(id);

function show(id, visible) {
	$(id).classList.toggle("hidden", !visible);
}

function status(text, kind) {
	const node = $("status");
	node.textContent = text || "";
	node.classList.toggle("problem", kind === "problem");
	node.classList.toggle("good", kind === "good");
}

function send(payload) {
	if (websocket && websocket.readyState === 1) {
		websocket.send(JSON.stringify({ event: "sendToPlugin", action, context: uuid, payload }));
	}
}

function save() {
	if (websocket && websocket.readyState === 1) {
		websocket.send(JSON.stringify({ event: "setSettings", context: uuid, payload: settings }));
	}
}

function set(key, value) {
	if (settings[key] === value) {
		return;
	}
	settings[key] = value;
	save();
}

// ---------------------------------------------------------------- layout

function layout() {
	const scope = SCOPE[action] || SCOPE[ACTION.CONTROL];
	const isMap = action === ACTION.MAP;
	// Scoped lists (categories, sound effects) are short enough already; the general control list is not.
	show("sectionItem", !isMap && !scope.prefix);
	show("controlItem", !isMap);
	show("controlIdItem", !isMap);
	show("mapItem", isMap);
	show("mapIdItem", isMap);
	show("levelItem", isMap);
	show("modeItem", action === ACTION.VALUE);
	show("amountItem", action === ACTION.VALUE);
	show("indexItem", action === ACTION.DROPDOWN);
	// Only controls that carry a value can show one; everything can show its name.
	for (const option of $("title").options) {
		option.hidden = option.value === "value" && (isMap || action === ACTION.MUSIC || action === ACTION.EFFECT);
	}
	$("controlItem").querySelector(".sdpi-item-label").textContent =
		action === ACTION.MUSIC ? "Category" : action === ACTION.EFFECT ? "Sound effect" : "Control";
	if (scope.prefix) {
		$("pasteHint").innerHTML =
			"Right-click the entry in the audio overlay or library and choose <b>Copy API URL</b> or " +
			"<b>Copy key image URL</b>, then paste it here. Ids survive renaming the entry.";
	}
}

/** Controls of the current action's kind, before the section filter narrows them further. */
function inScope() {
	const scope = SCOPE[action] || SCOPE[ACTION.CONTROL];
	return (controls || []).filter(
		(control) =>
			(scope.types.length === 0 || scope.types.includes(control.type)) &&
			(!scope.prefix || control.id.startsWith(scope.prefix)) &&
			!scope.exclude.some((prefix) => control.id.startsWith(prefix)),
	);
}

function sectionOf(control) {
	return control.id.split(".")[0];
}

function fillSections() {
	const select = $("section");
	const sections = [...new Set(inScope().map(sectionOf))].sort();
	select.innerHTML = "";
	select.appendChild(new Option("All sections", ""));
	for (const name of sections) {
		select.appendChild(new Option(name.charAt(0).toUpperCase() + name.slice(1), name));
	}
	// Follow the configured control when its section is still known, so reopening the inspector lands on it.
	const current = String(settings.controlId || "").split(".")[0];
	select.value = sections.includes(settings.section) ? settings.section : sections.includes(current) ? current : "";
	// Persist, so a later pick of the same section is not swallowed as "unchanged" by set().
	set("section", select.value);
}

function fillControls() {
	const select = $("control");
	select.innerHTML = "";
	const scope = SCOPE[action] || SCOPE[ACTION.CONTROL];
	if (!controls) {
		select.appendChild(new Option("Could not reach the application - type an id below", ""));
		select.disabled = true;
		return;
	}
	select.disabled = false;
	fillSections();
	const section = scope.prefix ? "" : settings.section || "";
	const matching = inScope().filter((control) => !section || sectionOf(control) === section);
	select.appendChild(new Option(matching.length ? "Choose..." : "Nothing of this kind is available", ""));
	for (const control of matching) {
		// The section hint only helps when the list spans sections; a filtered list repeats itself.
		const hint = scope.prefix || section ? "" : ` (${sectionOf(control)})`;
		select.appendChild(new Option((control.tooltip || control.id) + hint, control.id));
	}
	const current = String(settings.controlId || "");
	if (current && !matching.some((control) => control.id === current)) {
		select.appendChild(new Option(`${current} (not available now)`, current));
	}
	select.value = current;
	fillIndexOptions();
}

function fillIndexOptions() {
	if (action !== ACTION.DROPDOWN) {
		return;
	}
	const select = $("index");
	const chosen = Number(settings.index) || 0;
	select.innerHTML = "";
	const control = (controls || []).find((entry) => entry.id === settings.controlId);
	const choices = (control && control.choices) || [];
	if (choices.length === 0) {
		select.appendChild(new Option(`Option ${chosen} (list unavailable)`, String(chosen)));
	} else {
		choices.forEach((choice, position) => select.appendChild(new Option(`${position}: ${choice}`, String(position))));
	}
	select.value = String(chosen);
}

function fillMaps() {
	const select = $("map");
	select.innerHTML = "";
	if (!maps) {
		select.appendChild(new Option("Could not reach the application - type an id below", ""));
		select.disabled = true;
		return;
	}
	select.disabled = false;
	select.appendChild(new Option(maps.length ? "Choose..." : "The library is empty", ""));
	for (const map of maps) {
		select.appendChild(new Option(map.name, map.id));
	}
	const current = String(settings.mapId || "");
	if (current && !maps.some((map) => map.id === current)) {
		select.appendChild(new Option(current + " (not in the library)", current));
	}
	select.value = current;
}

function applySettings() {
	$("controlId").value = settings.controlId || "";
	$("mapId").value = settings.mapId || "";
	$("level").value = settings.level === undefined ? "" : settings.level;
	$("mode").value = settings.mode || "set";
	$("amount").value = settings.amount === undefined ? "" : settings.amount;
	$("title").value = settings.title || "none";
	$("imageUrl").value = settings.imageUrl || "";
	fillControls();
	fillMaps();
}

// ---------------------------------------------------------------- events

function wire() {
	$("section").addEventListener("change", (event) => {
		set("section", event.target.value);
		fillControls();
	});
	$("control").addEventListener("change", (event) => {
		set("controlId", event.target.value);
		fillIndexOptions();
	});
	$("controlId").addEventListener("input", (event) => {
		set("controlId", event.target.value.trim());
		$("control").value = settings.controlId;
	});
	$("map").addEventListener("change", (event) => {
		set("mapId", event.target.value);
		// Remember the name so a "Name" title shows something before the map list has been fetched.
		set("mapName", event.target.selectedOptions[0] ? event.target.selectedOptions[0].textContent : "");
		$("mapId").value = event.target.value;
	});
	$("mapId").addEventListener("input", (event) => set("mapId", event.target.value.trim()));
	$("level").addEventListener("input", (event) => set("level", event.target.value.trim()));
	$("mode").addEventListener("change", (event) => set("mode", event.target.value));
	$("amount").addEventListener("input", (event) => set("amount", event.target.value.trim()));
	$("index").addEventListener("change", (event) => set("index", Number(event.target.value)));
	$("title").addEventListener("change", (event) => set("title", event.target.value));
	$("imageUrl").addEventListener("input", (event) => set("imageUrl", event.target.value.trim()));
	$("paste").addEventListener("input", (event) => {
		const text = event.target.value.trim();
		if (text) {
			send({ kind: "parseUrl", url: text });
		}
	});
	for (const id of ["host", "port", "pollMs", "confirmPress"]) {
		$(id).addEventListener("change", () =>
			send({
				kind: "setGlobal",
				host: $("host").value.trim(),
				port: Number($("port").value),
				pollMs: Number($("pollMs").value),
				confirmPress: $("confirmPress").checked,
			}),
		);
	}
	$("reload").addEventListener("click", () => {
		controls = null;
		maps = null;
		status("Loading...", null);
		send({ kind: "refresh" });
		request();
	});
}

function request() {
	send({ kind: action === ACTION.MAP ? "listMaps" : "listControls" });
}

/** Applies a URL that the user pasted; one paste configures address, target and artwork. */
function applyParsed(parsed) {
	if (!parsed) {
		status("That does not look like a URL copied from the application.", "problem");
		return;
	}
	if (parsed.kind === "map") {
		if (action !== ACTION.MAP) {
			status("That is a map URL; use the \"Switch map\" action for it.", "problem");
			return;
		}
		settings.mapId = parsed.mapId;
		if (parsed.query && parsed.query.level !== undefined) {
			settings.level = parsed.query.level;
		}
	} else {
		if (action === ACTION.MAP) {
			status("That is a control URL; use one of the control actions for it.", "problem");
			return;
		}
		settings.controlId = parsed.controlId;
		if (parsed.kind === "image") {
			settings.imageUrl = "";
		}
		if (action === ACTION.DROPDOWN && parsed.query && parsed.query.index !== undefined) {
			settings.index = Number(parsed.query.index);
		}
		if (action === ACTION.VALUE && parsed.query) {
			for (const mode of ["increment", "decrement", "value"]) {
				if (parsed.query[mode] !== undefined) {
					settings.mode = mode === "value" ? "set" : mode;
					settings.amount = parsed.query[mode];
				}
			}
		}
	}
	save();
	applySettings();
	$("paste").value = "";
	send({
		kind: "setGlobal",
		host: parsed.address.host,
		port: parsed.address.port,
		pollMs: Number($("pollMs").value) || 1000,
		confirmPress: $("confirmPress").checked,
	});
	status("Applied the pasted URL.", "good");
}

function message(event) {
	const data = JSON.parse(event.data);
	if (data.event === "didReceiveSettings") {
		settings = (data.payload && data.payload.settings) || {};
		applySettings();
		return;
	}
	if (data.event !== "sendToPropertyInspector") {
		return;
	}
	const payload = data.payload || {};
	if (payload.kind === "global") {
		$("host").value = payload.global.host;
		$("port").value = payload.global.port;
		$("pollMs").value = payload.global.pollMs;
		$("confirmPress").checked = payload.global.confirmPress === true;
		return;
	}
	if (payload.kind === "controls") {
		controls = payload.controls;
		fillControls();
		status(payload.controls ? "" : "Could not reach the application: " + payload.error, payload.controls ? null : "problem");
		return;
	}
	if (payload.kind === "maps") {
		maps = payload.maps;
		fillMaps();
		status(payload.maps ? "" : "Could not reach the application: " + payload.error, payload.maps ? null : "problem");
		return;
	}
	if (payload.kind === "parsedUrl") {
		applyParsed(payload.parsed);
	}
}

// Called by Stream Deck once the inspector page is loaded.
// eslint-disable-next-line no-unused-vars
function connectElgatoStreamDeckSocket(port, inspectorUUID, registerEvent, info, actionInfo) {
	uuid = inspectorUUID;
	const parsed = typeof actionInfo === "string" ? JSON.parse(actionInfo) : actionInfo || {};
	action = parsed.action;
	settings = (parsed.payload && parsed.payload.settings) || {};
	websocket = new WebSocket("ws://127.0.0.1:" + port);
	websocket.onopen = () => {
		websocket.send(JSON.stringify({ event: registerEvent, uuid: inspectorUUID }));
		layout();
		wire();
		applySettings();
		request();
	};
	websocket.onmessage = message;
}
