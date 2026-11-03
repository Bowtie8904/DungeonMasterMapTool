/** Tile pyramid of a large map image (mirrors dmmt.render.ImagePyramidBuilder): level 0 is full resolution,
 * each level halves the size (rounding up), and level `levels` is the single overview image. */
export interface PyramidInfo {
  key: string;
  width: number;
  height: number;
  tileSize: number;
  levels: number;
  format: string;
}

export function levelSize(size: number, level: number): number {
  for (let i = 0; i < level; i++) size = Math.floor((size + 1) / 2);
  return size;
}

/** Finest level whose resolution still meets the screen: `devicePerImagePixel` screen pixels per source pixel. */
export function chooseLevel(p: PyramidInfo, devicePerImagePixel: number): number {
  if (!(devicePerImagePixel > 0)) return p.levels;
  const level = Math.floor(Math.log2(1 / devicePerImagePixel));
  return Math.max(0, Math.min(p.levels, level));
}

export function tileUrl(p: PyramidInfo, level: number, tx: number, ty: number): string {
  return `/t/${p.key}/${level}/${tx}_${ty}.${p.format}`;
}

export interface TileRect {
  tx: number;
  ty: number;
  /** Tile bounds in source-image pixels. */
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Tiles of `level` overlapping the source-pixel rectangle [x0,x1]x[y0,y1]. */
export function visibleTiles(p: PyramidInfo, level: number, x0: number, y0: number, x1: number, y1: number): TileRect[] {
  const lw = levelSize(p.width, level);
  const lh = levelSize(p.height, level);
  const fx = p.width / lw;
  const fy = p.height / lh;
  const cols = Math.ceil(lw / p.tileSize);
  const rows = Math.ceil(lh / p.tileSize);
  const c0 = Math.max(0, Math.floor(x0 / fx / p.tileSize));
  const c1 = Math.min(cols - 1, Math.floor(x1 / fx / p.tileSize));
  const r0 = Math.max(0, Math.floor(y0 / fy / p.tileSize));
  const r1 = Math.min(rows - 1, Math.floor(y1 / fy / p.tileSize));
  const out: TileRect[] = [];
  for (let ty = r0; ty <= r1; ty++) {
    for (let tx = c0; tx <= c1; tx++) {
      const px = tx * p.tileSize;
      const py = ty * p.tileSize;
      out.push({
        tx,
        ty,
        x: px * fx,
        y: py * fy,
        w: Math.min(p.tileSize, lw - px) * fx,
        h: Math.min(p.tileSize, lh - py) * fy,
      });
    }
  }
  return out;
}
