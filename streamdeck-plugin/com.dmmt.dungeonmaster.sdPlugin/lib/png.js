"use strict";

/**
 * Minimal dependency-free PNG decoder/encoder for 8-bit images, used to composite the highlight of a Stream Deck
 * key onto the artwork served by the application (plan.md 3.36.3). Only what the application produces and what
 * Stream Deck consumes is supported: 8 bits per channel, no interlacing.
 */

const zlib = require("node:zlib");

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const CRC_TABLE = (() => {
	const table = new Int32Array(256);
	for (let n = 0; n < 256; n++) {
		let c = n;
		for (let k = 0; k < 8; k++) {
			c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		}
		table[n] = c;
	}
	return table;
})();

function crc32(buffer) {
	let c = 0xffffffff;
	for (let i = 0; i < buffer.length; i++) {
		c = CRC_TABLE[(c ^ buffer[i]) & 0xff] ^ (c >>> 8);
	}
	return (c ^ 0xffffffff) >>> 0;
}

const CHANNELS = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };

/** Decodes a PNG into `{ width, height, pixels }` with `pixels` as straight RGBA bytes. */
function decode(buffer) {
	if (buffer.length < 8 || !buffer.subarray(0, 8).equals(SIGNATURE)) {
		throw new Error("Not a PNG image.");
	}
	let width = 0;
	let height = 0;
	let depth = 0;
	let colorType = 0;
	let palette = null;
	let transparency = null;
	const data = [];
	let offset = 8;
	while (offset + 8 <= buffer.length) {
		const length = buffer.readUInt32BE(offset);
		const type = buffer.toString("latin1", offset + 4, offset + 8);
		if (offset + 12 + length > buffer.length) {
			throw new Error("Truncated PNG chunk: " + type);
		}
		const body = buffer.subarray(offset + 8, offset + 8 + length);
		if (type === "IHDR") {
			width = body.readUInt32BE(0);
			height = body.readUInt32BE(4);
			depth = body[8];
			colorType = body[9];
			if (body[12] !== 0) {
				throw new Error("Interlaced PNG images are not supported.");
			}
		} else if (type === "PLTE") {
			palette = Buffer.from(body);
		} else if (type === "tRNS") {
			transparency = Buffer.from(body);
		} else if (type === "IDAT") {
			data.push(Buffer.from(body));
		} else if (type === "IEND") {
			break;
		}
		offset += 12 + length;
	}
	if (depth !== 8) {
		throw new Error("Only 8-bit PNG images are supported, got " + depth + " bits.");
	}
	const channels = CHANNELS[colorType];
	if (!channels || width <= 0 || height <= 0) {
		throw new Error("Unsupported PNG colour type: " + colorType);
	}
	const raw = zlib.inflateSync(Buffer.concat(data));
	const stride = width * channels;
	if (raw.length < (stride + 1) * height) {
		throw new Error("PNG image data is incomplete.");
	}
	const lines = Buffer.alloc(stride * height);
	for (let y = 0; y < height; y++) {
		const filter = raw[y * (stride + 1)];
		const source = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
		const target = lines.subarray(y * stride, (y + 1) * stride);
		const previous = y === 0 ? null : lines.subarray((y - 1) * stride, y * stride);
		unfilter(filter, source, target, previous, channels);
	}
	return { width, height, pixels: toRgba(lines, width, height, colorType, channels, palette, transparency) };
}

function unfilter(filter, source, target, previous, channels) {
	for (let x = 0; x < source.length; x++) {
		const a = x >= channels ? target[x - channels] : 0;
		const b = previous ? previous[x] : 0;
		const c = previous && x >= channels ? previous[x - channels] : 0;
		let value;
		switch (filter) {
			case 0:
				value = source[x];
				break;
			case 1:
				value = source[x] + a;
				break;
			case 2:
				value = source[x] + b;
				break;
			case 3:
				value = source[x] + ((a + b) >> 1);
				break;
			case 4:
				value = source[x] + paeth(a, b, c);
				break;
			default:
				throw new Error("Unknown PNG filter type: " + filter);
		}
		target[x] = value & 0xff;
	}
}

function paeth(a, b, c) {
	const p = a + b - c;
	const pa = Math.abs(p - a);
	const pb = Math.abs(p - b);
	const pc = Math.abs(p - c);
	if (pa <= pb && pa <= pc) {
		return a;
	}
	return pb <= pc ? b : c;
}

function toRgba(lines, width, height, colorType, channels, palette, transparency) {
	const pixels = Buffer.alloc(width * height * 4);
	for (let i = 0, p = 0; i < width * height; i++, p += 4) {
		const s = i * channels;
		if (colorType === 6) {
			lines.copy(pixels, p, s, s + 4);
		} else if (colorType === 2) {
			pixels[p] = lines[s];
			pixels[p + 1] = lines[s + 1];
			pixels[p + 2] = lines[s + 2];
			pixels[p + 3] = 255;
		} else if (colorType === 0) {
			pixels.fill(lines[s], p, p + 3);
			pixels[p + 3] = 255;
		} else if (colorType === 4) {
			pixels.fill(lines[s], p, p + 3);
			pixels[p + 3] = lines[s + 1];
		} else {
			const index = lines[s];
			if (!palette || index * 3 + 2 >= palette.length) {
				throw new Error("PNG palette entry is missing.");
			}
			pixels[p] = palette[index * 3];
			pixels[p + 1] = palette[index * 3 + 1];
			pixels[p + 2] = palette[index * 3 + 2];
			pixels[p + 3] = transparency && index < transparency.length ? transparency[index] : 255;
		}
	}
	return pixels;
}

/** Encodes straight RGBA bytes as an 8-bit RGBA PNG. */
function encode({ width, height, pixels }) {
	const stride = width * 4;
	const raw = Buffer.alloc((stride + 1) * height);
	for (let y = 0; y < height; y++) {
		raw[y * (stride + 1)] = 0;
		pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
	}
	const header = Buffer.alloc(13);
	header.writeUInt32BE(width, 0);
	header.writeUInt32BE(height, 4);
	header[8] = 8;
	header[9] = 6;
	return Buffer.concat([
		SIGNATURE,
		chunk("IHDR", header),
		chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
		chunk("IEND", Buffer.alloc(0)),
	]);
}

function chunk(type, body) {
	const length = Buffer.alloc(4);
	length.writeUInt32BE(body.length, 0);
	const typed = Buffer.concat([Buffer.from(type, "latin1"), body]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(typed), 0);
	return Buffer.concat([length, typed, crc]);
}

module.exports = { decode, encode };
