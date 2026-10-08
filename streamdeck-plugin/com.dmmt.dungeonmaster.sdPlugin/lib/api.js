"use strict";

/**
 * HTTP access to the Dungeon Master Map Tool local control API.
 *
 * All requests are made from the plugin's Node process with `node:http`, never from the property inspector: the
 * inspector is a browser view, and the API deliberately rejects requests that carry browser origin headers.
 */

const http = require("node:http");

const TIMEOUT_MS = 4000;
const IMAGE_TIMEOUT_MS = 8000;

// Keep-alive avoids a new TCP handshake for every poll.
const agent = new http.Agent({ keepAlive: true, maxSockets: 4, keepAliveMsecs: 15000 });

class ApiError extends Error {
	constructor(message, status) {
		super(message);
		this.status = status || 0;
	}
}

function encodePath(segments) {
	return segments.map((segment) => encodeURIComponent(segment)).join("/");
}

function buildPath(path, query) {
	const entries = Object.entries(query || {}).filter(([, value]) => value !== undefined && value !== null);
	if (entries.length === 0) {
		return path;
	}
	return (
		path +
		"?" +
		entries.map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`).join("&")
	);
}

function get(address, path, query, { binary = false, timeout = TIMEOUT_MS } = {}) {
	return new Promise((resolve, reject) => {
		const options = {
			host: address.host,
			port: address.port,
			path: buildPath(path, query),
			method: "GET",
			agent,
			headers: { Accept: binary ? "image/png" : "application/json", Connection: "keep-alive" },
		};
		const request = http.request(options, (response) => {
			const chunks = [];
			response.on("data", (chunk) => chunks.push(chunk));
			response.on("end", () => {
				const body = Buffer.concat(chunks);
				if (response.statusCode !== 200) {
					let message = `HTTP ${response.statusCode}`;
					try {
						const parsed = JSON.parse(body.toString("utf8"));
						if (parsed && parsed.error) {
							message = parsed.error;
						}
					} catch {
						// A non-JSON error body is reported by status code alone.
					}
					reject(new ApiError(message, response.statusCode));
					return;
				}
				if (binary) {
					resolve(body);
					return;
				}
				try {
					resolve(JSON.parse(body.toString("utf8")));
				} catch (error) {
					reject(new ApiError("The application returned a malformed response.", 0));
				}
			});
		});
		request.setTimeout(timeout, () => request.destroy(new ApiError("The application did not answer in time.", 0)));
		request.on("error", (error) =>
			reject(error instanceof ApiError ? error : new ApiError(friendly(error), 0)),
		);
		request.end();
	});
}

function friendly(error) {
	switch (error.code) {
		case "ECONNREFUSED":
			return "No answer: start the application and enable Settings > Local control API.";
		case "EHOSTUNREACH":
		case "ENETUNREACH":
			return "The application's computer cannot be reached on this network.";
		case "ETIMEDOUT":
			return "The application did not answer in time.";
		default:
			return error.message || "The request failed.";
	}
}

/** `GET /api/controls`, optionally limited to the given dotted control ids. */
function describe(address, ids) {
	return get(address, "/api/controls", ids && ids.length ? { ids: ids.join(",") } : {});
}

/** Executes a control command and returns its new described state. */
function execute(address, controlId, params) {
	return get(address, "/api/controls/" + encodePath(controlId.split(".")), params);
}

function state(address) {
	return get(address, "/api/state", {});
}

function maps(address) {
	return get(address, "/api/maps", {});
}

function switchMap(address, mapId, level) {
	return get(address, "/api/maps/" + encodeURIComponent(mapId) + "/switch", level === "" || level === undefined || level === null ? {} : { level });
}

/** Raw PNG bytes of a control's key image. */
function keyImage(address, controlId, operation) {
	return get(address, "/api/controls/" + encodePath(controlId.split(".")) + "/image", operation ? { operation } : {}, {
		binary: true,
		timeout: IMAGE_TIMEOUT_MS,
	});
}

/** PNG bytes of an arbitrary key image URL that the user pasted. */
function fetchUrl(url) {
	const parsed = new URL(url);
	if (parsed.protocol !== "http:") {
		throw new ApiError("Key image URLs must use http://.", 0);
	}
	return get(
		{ host: parsed.hostname, port: parsed.port || 80 },
		parsed.pathname,
		Object.fromEntries(parsed.searchParams.entries()),
		{ binary: true, timeout: IMAGE_TIMEOUT_MS },
	);
}

/**
 * Understands every URL the application's right-click menu copies - command URL, index URL, increment/decrement
 * URL, key image URL and map switch URL - so one paste configures an action completely (plan.md 3.36.2).
 */
function parseCopiedUrl(text) {
	let url;
	try {
		url = new URL(String(text || "").trim());
	} catch {
		return null;
	}
	if (url.protocol !== "http:") {
		return null;
	}
	const address = { host: url.hostname, port: Number(url.port || 80) };
	const segments = url.pathname.split("/").filter((segment) => segment.length > 0).map(decodeURIComponent);
	const query = Object.fromEntries(url.searchParams.entries());
	if (segments[0] !== "api") {
		return null;
	}
	if (segments[1] === "controls" && segments.length > 2) {
		const image = segments[segments.length - 1] === "image";
		const parts = segments.slice(2, image ? segments.length - 1 : segments.length);
		if (parts.length === 0) {
			return null;
		}
		return { kind: image ? "image" : "control", address, controlId: parts.join("."), query };
	}
	if (segments[1] === "maps" && segments.length === 4 && segments[3] === "switch") {
		return { kind: "map", address, mapId: segments[2], query };
	}
	return null;
}

module.exports = {
	ApiError,
	describe,
	execute,
	state,
	maps,
	switchMap,
	keyImage,
	fetchUrl,
	parseCopiedUrl,
};
