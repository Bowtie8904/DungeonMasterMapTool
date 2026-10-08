"use strict";

/**
 * Generates the plugin's PNG artwork (plugin icon, category icon, action icons and default key images) with the
 * plugin's own PNG encoder, so the repository needs no binary image tooling.
 *
 * Run with: node tools/make-icons.js
 */

const fs = require("node:fs");
const path = require("node:path");
const png = require("../com.dmmt.dungeonmaster.sdPlugin/lib/png");

const ROOT = path.join(__dirname, "..", "com.dmmt.dungeonmaster.sdPlugin", "imgs");

const PALETTE = {
	background: [0x18, 0x21, 0x2d, 0xff],
	transparent: [0, 0, 0, 0],
	accent: [0x4f, 0xc3, 0xf7, 0xff],
	white: [0xf2, 0xf6, 0xfa, 0xff],
	amber: [0xf2, 0xb6, 0x4c, 0xff],
};

function canvas(size, fill) {
	const pixels = Buffer.alloc(size * size * 4);
	if (fill) {
		for (let i = 0; i < size * size; i++) {
			pixels.set(fill, i * 4);
		}
	}
	return { width: size, height: size, pixels };
}

function blend(image, x, y, color, alpha) {
	if (x < 0 || y < 0 || x >= image.width || y >= image.height || alpha <= 0) {
		return;
	}
	const p = (Math.floor(y) * image.width + Math.floor(x)) * 4;
	const a = Math.min(1, alpha) * (color[3] / 255);
	const base = image.pixels[p + 3] / 255;
	const out = a + base * (1 - a);
	for (let c = 0; c < 3; c++) {
		const src = color[c] * a;
		const dst = image.pixels[p + c] * base * (1 - a);
		image.pixels[p + c] = out === 0 ? 0 : Math.round((src + dst) / out);
	}
	image.pixels[p + 3] = Math.round(out * 255);
}

function disc(image, cx, cy, radius, color) {
	for (let y = Math.floor(cy - radius) - 1; y <= cy + radius + 1; y++) {
		for (let x = Math.floor(cx - radius) - 1; x <= cx + radius + 1; x++) {
			const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
			blend(image, x, y, color, Math.min(1, radius - d + 0.5));
		}
	}
}

function ring(image, cx, cy, radius, thickness, color) {
	for (let y = Math.floor(cy - radius) - 1; y <= cy + radius + 1; y++) {
		for (let x = Math.floor(cx - radius) - 1; x <= cx + radius + 1; x++) {
			const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
			blend(image, x, y, color, Math.min(1, radius - d + 0.5, d - (radius - thickness) + 0.5));
		}
	}
}

function rect(image, x0, y0, w, h, color, radius = 0) {
	for (let y = Math.floor(y0); y < y0 + h; y++) {
		for (let x = Math.floor(x0); x < x0 + w; x++) {
			let alpha = 1;
			if (radius > 0) {
				const dx = Math.max(x0 + radius - (x + 0.5), x + 0.5 - (x0 + w - radius), 0);
				const dy = Math.max(y0 + radius - (y + 0.5), y + 0.5 - (y0 + h - radius), 0);
				alpha = Math.min(1, radius - Math.hypot(dx, dy) + 0.5);
			}
			blend(image, x, y, color, alpha);
		}
	}
}

function triangle(image, points, color) {
	polygon(image, points, color);
}

/** Fills a convex or concave polygon with 3x3 supersampling, so the diagonal fold edges are not jagged. */
function polygon(image, points, color) {
	const xs = points.map((p) => p[0]);
	const ys = points.map((p) => p[1]);
	const inside = (px, py) => {
		let hit = false;
		for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
			const [xi, yi] = points[i];
			const [xj, yj] = points[j];
			if (yi > py !== yj > py && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) {
				hit = !hit;
			}
		}
		return hit;
	};
	for (let y = Math.floor(Math.min(...ys)); y <= Math.max(...ys); y++) {
		for (let x = Math.floor(Math.min(...xs)); x <= Math.max(...xs); x++) {
			let hits = 0;
			for (let sy = 0; sy < 3; sy++) {
				for (let sx = 0; sx < 3; sx++) {
					if (inside(x + (sx + 0.5) / 3, y + (sy + 0.5) / 3)) {
						hits++;
					}
				}
			}
			blend(image, x, y, color, hits / 9);
		}
	}
}

const GLYPHS = {
	/** A folded paper map: three panels with alternating fold heights. The plugin's identity and the map action. */
	map(image, s, color) {
		// The panel boundaries and the zigzag the folds cut into the top and bottom edge.
		const xs = [0.1, 0.366, 0.633, 0.9].map((v) => v * s);
		const top = [0.215, 0.12, 0.215, 0.12].map((v) => v * s);
		const bottom = [0.88, 0.785, 0.88, 0.785].map((v) => v * s);
		const gap = s * 0.018;
		const at = (edge, panel, x) => {
			const t = (x - xs[panel]) / (xs[panel + 1] - xs[panel]);
			return edge[panel] + (edge[panel + 1] - edge[panel]) * t;
		};
		for (let panel = 0; panel < 3; panel++) {
			const left = panel === 0 ? xs[0] : xs[panel] + gap;
			const right = panel === 2 ? xs[3] : xs[panel + 1] - gap;
			polygon(
				image,
				[
					[left, at(top, panel, left)],
					[right, at(top, panel, right)],
					[right, at(bottom, panel, right)],
					[left, at(bottom, panel, left)],
				],
				color,
			);
		}
	},
	/** A switch for the generic control toggle. */
	toggle(image, s, color, background) {
		rect(image, s * 0.14, s * 0.36, s * 0.72, s * 0.28, color, s * 0.14);
		disc(image, s * 0.67, s * 0.5, s * 0.11, background);
	},
	/** A slider with a handle for the value action. */
	slider(image, s, color, background) {
		rect(image, s * 0.14, s * 0.47, s * 0.72, s * 0.06, color, s * 0.03);
		disc(image, s * 0.62, s * 0.5, s * 0.14, color);
		disc(image, s * 0.62, s * 0.5, s * 0.065, background);
	},
	/** A list with a caret for the dropdown action. */
	dropdown(image, s, color) {
		rect(image, s * 0.16, s * 0.26, s * 0.68, s * 0.09, color, s * 0.045);
		rect(image, s * 0.16, s * 0.45, s * 0.68, s * 0.09, color, s * 0.045);
		triangle(
			image,
			[
				[s * 0.35, s * 0.64],
				[s * 0.65, s * 0.64],
				[s * 0.5, s * 0.81],
			],
			color,
		);
	},
	/** A music note for the music category action. */
	note(image, s, color) {
		rect(image, s * 0.52, s * 0.18, s * 0.08, s * 0.47, color, s * 0.035);
		rect(image, s * 0.52, s * 0.18, s * 0.26, s * 0.09, color, s * 0.035);
		disc(image, s * 0.43, s * 0.67, s * 0.145, color);
	},
	/** Concentric waves for the sound effect action. */
	waves(image, s, color) {
		disc(image, s * 0.26, s * 0.5, s * 0.08, color);
		for (const radius of [0.24, 0.4]) {
			for (let a = -Math.PI / 2.6; a <= Math.PI / 2.6; a += 0.004) {
				disc(image, s * 0.26 + Math.cos(a) * s * radius, s * 0.5 + Math.sin(a) * s * radius, s * 0.03, color);
			}
		}
	},
};

function draw(size, glyph, color, withBackground) {
	const image = canvas(size, withBackground ? PALETTE.background : null);
	GLYPHS[glyph](image, size, color, withBackground ? PALETTE.background : PALETTE.transparent);
	return image;
}

function pair(file, glyph, color, withBackground, size) {
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file + ".png", png.encode(draw(size, glyph, color, withBackground)));
	fs.writeFileSync(file + "@2x.png", png.encode(draw(size * 2, glyph, color, withBackground)));
}

const ACTIONS = [
	["control", "toggle", PALETTE.accent],
	["value", "slider", PALETTE.accent],
	["dropdown", "dropdown", PALETTE.accent],
	["music", "note", PALETTE.amber],
	["effect", "waves", PALETTE.amber],
	["map", "map", PALETTE.accent],
];

pair(path.join(ROOT, "plugin", "icon"), "map", PALETTE.accent, true, 144);
pair(path.join(ROOT, "plugin", "category"), "map", PALETTE.accent, false, 28);
// Library maps have no control in the application and therefore no key image endpoint, so the plugin ships its
// own generic map key artwork, drawn like the application's control images. This one is composited at runtime
// rather than handed to Stream Deck as a file, so it needs no @2x variant.
fs.mkdirSync(path.join(ROOT, "keys"), { recursive: true });
fs.writeFileSync(path.join(ROOT, "keys", "map.png"), png.encode(draw(144, "map", PALETTE.accent, true)));
for (const [name, glyph, color] of ACTIONS) {
	pair(path.join(ROOT, "actions", name, "icon"), glyph, PALETTE.white, false, 20);
	pair(path.join(ROOT, "actions", name, "key"), glyph, color, true, 72);
}
process.stdout.write("Wrote plugin artwork to " + ROOT + "\n");
