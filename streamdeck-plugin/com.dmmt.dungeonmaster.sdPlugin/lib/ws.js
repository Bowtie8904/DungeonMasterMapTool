"use strict";

/**
 * Tiny RFC 6455 client for the Stream Deck registration socket. Stream Deck's Node 20 runtime has no global
 * `WebSocket`, and the plugin deliberately ships without npm dependencies, so the few frames that are actually
 * needed (text, ping/pong, close) are implemented here.
 */

const net = require("node:net");
const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");

const MAX_MESSAGE = 8 * 1024 * 1024;

class WebSocketClient extends EventEmitter {
	constructor(port) {
		super();
		this.open = false;
		this.buffer = Buffer.alloc(0);
		this.handshakeDone = false;
		this.fragments = [];
		this.fragmentOpcode = 0;
		this.socket = net.connect({ host: "127.0.0.1", port }, () => this.#handshake(port));
		this.socket.on("data", (chunk) => this.#receive(chunk));
		this.socket.on("error", (error) => this.emit("error", error));
		this.socket.on("close", () => {
			this.open = false;
			this.emit("close");
		});
	}

	#handshake(port) {
		this.key = crypto.randomBytes(16).toString("base64");
		this.socket.write(
			"GET / HTTP/1.1\r\n" +
				`Host: 127.0.0.1:${port}\r\n` +
				"Upgrade: websocket\r\n" +
				"Connection: Upgrade\r\n" +
				`Sec-WebSocket-Key: ${this.key}\r\n` +
				"Sec-WebSocket-Version: 13\r\n\r\n",
		);
	}

	#receive(chunk) {
		this.buffer = Buffer.concat([this.buffer, chunk]);
		if (!this.handshakeDone) {
			const end = this.buffer.indexOf("\r\n\r\n");
			if (end < 0) {
				if (this.buffer.length > 16384) {
					this.#fail("WebSocket handshake response is too large.");
				}
				return;
			}
			const head = this.buffer.toString("latin1", 0, end);
			if (!/^HTTP\/1\.1 101/i.test(head)) {
				this.#fail("Stream Deck refused the WebSocket handshake: " + head.split("\r\n")[0]);
				return;
			}
			const expected = crypto
				.createHash("sha1")
				.update(this.key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
				.digest("base64");
			const accept = /sec-websocket-accept:\s*(\S+)/i.exec(head);
			if (!accept || accept[1] !== expected) {
				this.#fail("Stream Deck returned an invalid WebSocket accept key.");
				return;
			}
			this.buffer = this.buffer.subarray(end + 4);
			this.handshakeDone = true;
			this.open = true;
			this.emit("open");
		}
		this.#frames();
	}

	#frames() {
		for (;;) {
			const frame = this.#readFrame();
			if (!frame) {
				return;
			}
			const { fin, opcode, payload } = frame;
			if (opcode === 0x8) {
				this.close();
				return;
			}
			if (opcode === 0x9) {
				this.#send(0xa, payload);
				continue;
			}
			if (opcode === 0xa) {
				continue;
			}
			if (opcode === 0x0) {
				this.fragments.push(payload);
			} else {
				this.fragments = [payload];
				this.fragmentOpcode = opcode;
			}
			if (!fin) {
				continue;
			}
			const message = Buffer.concat(this.fragments);
			this.fragments = [];
			if (this.fragmentOpcode === 0x1) {
				this.emit("message", message.toString("utf8"));
			}
		}
	}

	#readFrame() {
		const data = this.buffer;
		if (data.length < 2) {
			return null;
		}
		const fin = (data[0] & 0x80) !== 0;
		const opcode = data[0] & 0x0f;
		const masked = (data[1] & 0x80) !== 0;
		let length = data[1] & 0x7f;
		let offset = 2;
		if (length === 126) {
			if (data.length < 4) {
				return null;
			}
			length = data.readUInt16BE(2);
			offset = 4;
		} else if (length === 127) {
			if (data.length < 10) {
				return null;
			}
			const big = data.readBigUInt64BE(2);
			if (big > BigInt(MAX_MESSAGE)) {
				this.#fail("WebSocket frame is too large.");
				return null;
			}
			length = Number(big);
			offset = 10;
		}
		const maskOffset = offset;
		if (masked) {
			offset += 4;
		}
		if (data.length < offset + length) {
			return null;
		}
		const payload = Buffer.from(data.subarray(offset, offset + length));
		if (masked) {
			for (let i = 0; i < payload.length; i++) {
				payload[i] ^= data[maskOffset + (i % 4)];
			}
		}
		this.buffer = data.subarray(offset + length);
		return { fin, opcode, payload };
	}

	#send(opcode, payload) {
		if (!this.open) {
			return;
		}
		const mask = crypto.randomBytes(4);
		const masked = Buffer.from(payload);
		for (let i = 0; i < masked.length; i++) {
			masked[i] ^= mask[i % 4];
		}
		let header;
		if (masked.length < 126) {
			header = Buffer.from([0x80 | opcode, 0x80 | masked.length]);
		} else if (masked.length < 65536) {
			header = Buffer.alloc(4);
			header[0] = 0x80 | opcode;
			header[1] = 0x80 | 126;
			header.writeUInt16BE(masked.length, 2);
		} else {
			header = Buffer.alloc(10);
			header[0] = 0x80 | opcode;
			header[1] = 0x80 | 127;
			header.writeBigUInt64BE(BigInt(masked.length), 2);
		}
		this.socket.write(Buffer.concat([header, mask, masked]));
	}

	send(text) {
		this.#send(0x1, Buffer.from(text, "utf8"));
	}

	close() {
		if (this.open) {
			this.#send(0x8, Buffer.alloc(0));
			this.open = false;
		}
		this.socket.end();
	}

	#fail(message) {
		this.emit("error", new Error(message));
		this.open = false;
		this.socket.destroy();
	}
}

module.exports = { WebSocketClient };
