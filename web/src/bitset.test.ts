import { describe, expect, it } from 'vitest';
import { deflateSync, inflateSync } from 'node:zlib';
import { base64ToBytes, bitsToFlags, bytesToBase64, decodeBitset, encodeBitset, flagsToBits } from './bitset';

describe('bitset codec', () => {
  it('reads Java BitSet little-endian layout', () => {
    // bits 0, 3, 9 -> bytes [0b00001001, 0b00000010]
    const flags = bitsToFlags(Uint8Array.from([0x09, 0x02]), 16);
    expect([...flags.keys()].filter((i) => flags[i])).toEqual([0, 3, 9]);
  });

  it('flagsToBits trims trailing zero bytes', () => {
    const flags = new Uint8Array(40);
    flags[0] = 1;
    flags[9] = 1;
    expect([...flagsToBits(flags)]).toEqual([0x01, 0x02]);
    expect(flagsToBits(new Uint8Array(16)).length).toBe(0);
  });

  it('decodes zlib data produced by a Java-like Deflater', async () => {
    const b64 = bytesToBase64(new Uint8Array(deflateSync(Buffer.from([0x09, 0x02]))));
    const flags = await decodeBitset(b64, 12);
    expect(flags[0]).toBe(1);
    expect(flags[3]).toBe(1);
    expect(flags[9]).toBe(1);
    expect(flags[1]).toBe(0);
  });

  it('round trips and is readable as zlib', async () => {
    const flags = new Uint8Array(100);
    flags[7] = flags[63] = flags[99] = 1;
    const b64 = await encodeBitset(flags);
    expect([...inflateSync(Buffer.from(base64ToBytes(b64)))]).toEqual([...flagsToBits(flags)]);
    expect([...(await decodeBitset(b64, 100))]).toEqual([...flags]);
  });

  it('treats missing data as empty', async () => {
    expect((await decodeBitset(null, 8)).every((v) => v === 0)).toBe(true);
  });
});
