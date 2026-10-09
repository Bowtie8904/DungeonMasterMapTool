"use strict";

/**
 * Builds the base64 PNG a key shows (plan.md 3.36.3). The artwork comes from the application's key image
 * endpoint; this module only adds the state: an accent border while the control is active, a dim veil while it
 * is inactive, a grey veil for disabled controls and a red border when the control is unknown or the
 * application cannot be reached.
 */

const png = require("./png");

const SIZE = 144;
const BORDER = 7;

const APPEARANCE = {
	// [border colour or null, veil colour, veil strength]
	active: ["#4FC3F7", null, 0],
	inactive: [null, "#070A0F", 0.42],
	disabled: ["#4A5564", "#070A0F", 0.68],
	error: ["#E05252", "#140707", 0.55],
};

/** Flat fallback artwork for keys without a reachable key image. */
function placeholder() {
	const pixels = Buffer.alloc(SIZE * SIZE * 4);
	for (let i = 0; i < SIZE * SIZE; i++) {
		pixels[i * 4] = 0x18;
		pixels[i * 4 + 1] = 0x21;
		pixels[i * 4 + 2] = 0x2d;
		pixels[i * 4 + 3] = 0xff;
	}
	return { width: SIZE, height: SIZE, pixels };
}

function parse(buffer) {
	return png.decode(buffer);
}

/** A solid key image for a configured colour-picker value. */
function solid(hex) {
	if (!/^#[0-9a-f]{6}$/i.test(String(hex || ""))) {
		return null;
	}
	const [r, g, b] = rgb(hex);
	const pixels = Buffer.alloc(SIZE * SIZE * 4);
	for (let i = 0; i < pixels.length; i += 4) {
		pixels[i] = r;
		pixels[i + 1] = g;
		pixels[i + 2] = b;
		pixels[i + 3] = 0xff;
	}
	return { width: SIZE, height: SIZE, pixels };
}

function rgb(hex) {
	return [
		parseInt(hex.slice(1, 3), 16),
		parseInt(hex.slice(3, 5), 16),
		parseInt(hex.slice(5, 7), 16),
	];
}

function veil(image, hex, strength) {
	const [r, g, b] = rgb(hex);
	const pixels = image.pixels;
	for (let i = 0; i < pixels.length; i += 4) {
		pixels[i] = Math.round(pixels[i] * (1 - strength) + r * strength);
		pixels[i + 1] = Math.round(pixels[i + 1] * (1 - strength) + g * strength);
		pixels[i + 2] = Math.round(pixels[i + 2] * (1 - strength) + b * strength);
	}
}

function border(image, hex) {
	const [r, g, b] = rgb(hex);
	const { width, height, pixels } = image;
	for (let y = 0; y < height; y++) {
		for (let x = 0; x < width; x++) {
			const edge = Math.min(x, y, width - 1 - x, height - 1 - y);
			if (edge >= BORDER) {
				continue;
			}
			// Soften the innermost row so the frame does not look aliased on the key.
			const alpha = edge === BORDER - 1 ? 0.55 : 1;
			const p = (y * width + x) * 4;
			pixels[p] = Math.round(pixels[p] * (1 - alpha) + r * alpha);
			pixels[p + 1] = Math.round(pixels[p + 1] * (1 - alpha) + g * alpha);
			pixels[p + 2] = Math.round(pixels[p + 2] * (1 - alpha) + b * alpha);
			pixels[p + 3] = 255;
		}
	}
}

/**
 * Renders a key. `artwork` is the decoded key image (or null for the placeholder) and `state` is one of
 * `active`, `inactive`, `disabled` or `error`. Returns the base64 PNG Stream Deck expects in `setImage`.
 */
function render(artwork, state) {
	const source = artwork || placeholder();
	const image = {
		width: source.width,
		height: source.height,
		pixels: Buffer.from(source.pixels),
	};
	const [frame, shade, strength] = APPEARANCE[state] || APPEARANCE.inactive;
	if (shade) {
		veil(image, shade, strength);
	}
	if (frame) {
		border(image, frame);
	}
	return png.encode(image).toString("base64");
}

/** Keeps a configured color swatch true to its value; state is shown with a border, never a color-altering veil. */
function renderColor(hex, state) {
	const image = solid(hex);
	if (!image) {
		return render(null, "error");
	}
	const [frame] = APPEARANCE[state] || APPEARANCE.inactive;
	if (frame) {
		border(image, frame);
	}
	return png.encode(image).toString("base64");
}

module.exports = { parse, render, renderColor, solid, SIZE };
