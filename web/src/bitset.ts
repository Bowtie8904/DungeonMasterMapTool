export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let bin = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    bin += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(bin);
}

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const source = new Blob([bytes as BlobPart]).stream().pipeThrough(stream as unknown as ReadableWritablePair);
  return new Uint8Array(await new Response(source).arrayBuffer());
}

/** zlib-wrapped DEFLATE ("deflate" in the Streams API, java.util.zip.Deflater default). */
export function inflate(bytes: Uint8Array): Promise<Uint8Array> {
  return pipe(bytes, new DecompressionStream('deflate'));
}

export function deflate(bytes: Uint8Array): Promise<Uint8Array> {
  return pipe(bytes, new CompressionStream('deflate'));
}

/** java.util.BitSet.toByteArray() layout -> one byte (0/1) per bit for `count` bits. */
export function bitsToFlags(bytes: Uint8Array, count: number): Uint8Array {
  const flags = new Uint8Array(count);
  const n = Math.min(count, bytes.length * 8);
  for (let i = 0; i < n; i++) {
    flags[i] = (bytes[i >> 3] >> (i & 7)) & 1;
  }
  return flags;
}

/** Inverse of bitsToFlags; trailing zero bytes are trimmed like BitSet.toByteArray(). */
export function flagsToBits(flags: Uint8Array): Uint8Array {
  const bytes = new Uint8Array((flags.length + 7) >> 3);
  for (let i = 0; i < flags.length; i++) {
    if (flags[i]) {
      bytes[i >> 3] |= 1 << (i & 7);
    }
  }
  let end = bytes.length;
  while (end > 0 && bytes[end - 1] === 0) {
    end--;
  }
  return bytes.subarray(0, end);
}

export async function decodeBitset(b64: string | null | undefined, count: number): Promise<Uint8Array> {
  if (!b64) {
    return new Uint8Array(count);
  }
  const raw = await inflate(base64ToBytes(b64));
  return bitsToFlags(raw, count);
}

export async function encodeBitset(flags: Uint8Array): Promise<string> {
  return bytesToBase64(await deflate(flagsToBits(flags)));
}
