import { hostFetch, tokenArtwork, type TokenArtworkSettings } from './api';
import { el } from './login';

export function cropGeometry(width: number, height: number, settings: TokenArtworkSettings, edge = 280) {
  const scale = edge * settings.zoom / Math.min(width, height);
  return { scale, x: edge / 2 - settings.centerX * width * scale, y: edge / 2 - settings.centerY * height * scale };
}

export async function editTokenArtwork(actorId: string, canEdit: () => boolean): Promise<string | undefined> {
  const settings = await tokenArtwork(actorId);
  if (!canEdit()) throw new Error('This character sheet is read-only.');
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog token-artwork-dialog', ariaLabel: 'Customize character token' });
  const canvas = el('canvas', { width: 280, height: 280, tabIndex: 0, ariaLabel: 'Token preview. Drag to move the image.' });
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Cannot create the token preview.');
  const color = el('input', { type: 'color', value: settings.color });
  const size = el('input', { type: 'number', min: '.5', step: '.5', required: true, value: String(settings.sizeTiles) });
  const zoom = el('input', { type: 'range', min: '1', max: '10', step: '.01', value: String(settings.zoom) });
  const file = el('input', { type: 'file', accept: 'image/*', hidden: true });
  const choose = el('button', { type: 'button' }, 'Choose image...');
  const paste = el('button', { type: 'button' }, 'Paste image');
  const save = el('button', { type: 'button', class: 'primary' }, 'Save token');
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const error = el('p', { class: 'error', role: 'alert' });
  let source: HTMLImageElement | undefined;
  let image: string | undefined;
  let request = 0;
  let loading = false;
  let saving = false;
  let result: string | undefined;
  const draw = () => {
    const borderWidth = Math.max(2, 280 * .055);
    const radius = (280 - borderWidth) / 2;
    context.clearRect(0, 0, 280, 280);
    context.save();
    context.beginPath();
    context.arc(140, 140, radius, 0, Math.PI * 2);
    context.clip();
    context.fillStyle = settings.color;
    context.fillRect(0, 0, 280, 280);
    context.fillStyle = 'rgba(0,0,0,.5)';
    context.fillRect(0, 0, 280, 280);
    if (source) {
      const crop = cropGeometry(source.naturalWidth, source.naturalHeight, settings);
      context.drawImage(source, crop.x, crop.y, source.naturalWidth * crop.scale, source.naturalHeight * crop.scale);
    }
    context.restore();
    context.strokeStyle = settings.color;
    context.lineWidth = borderWidth;
    context.beginPath();
    context.arc(140, 140, radius, 0, Math.PI * 2);
    context.stroke();
    save.disabled = loading || saving || !source;
  };
  const load = async (blob: Blob, replace: boolean) => {
    if (saving || !dialog.isConnected) return;
    const id = ++request;
    loading = true;
    error.textContent = 'Reading image...';
    draw();
    try {
      if (!blob.size || blob.size > 8 * 1024 * 1024) throw new Error('Image is empty or larger than 8 MB.');
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(new Error('Cannot read this image.'));
        reader.readAsDataURL(blob);
      });
      const loaded = new Image();
      loaded.src = data;
      await loaded.decode();
      if (loaded.naturalWidth * loaded.naturalHeight > 40_000_000) throw new Error('Image exceeds the 40 million pixel limit.');
      if (id !== request || !dialog.isConnected) return;
      source = loaded;
      if (replace) {
        image = data;
        settings.zoom = 1;
        settings.centerX = settings.centerY = .5;
        zoom.value = '1';
      }
      error.textContent = '';
    } finally {
      if (id === request) { loading = false; draw(); }
    }
  };
  const report = (failure: unknown) => { error.textContent = (failure as Error).message; };
  choose.addEventListener('click', () => file.click());
  file.addEventListener('change', () => {
    const selected = file.files?.[0];
    if (selected) void load(selected, true).catch(report);
    file.value = '';
  });
  paste.addEventListener('click', () => {
    if (!navigator.clipboard?.read) { error.textContent = 'Clipboard access is unavailable. Press Ctrl+V to paste an image.'; return; }
    const id = ++request;
    loading = true;
    error.textContent = 'Reading clipboard...';
    draw();
    void navigator.clipboard.read().catch(() => { throw new Error('Cannot read the clipboard. Press Ctrl+V to paste an image.'); }).then(async items => {
      if (id !== request || !dialog.isConnected) return;
      for (const item of items) {
        const type = item.types.find(t => t.startsWith('image/'));
        if (type) {
          const blob = await item.getType(type);
          if (id === request) await load(blob, true).catch(report);
          return;
        }
      }
      throw new Error('The clipboard does not contain an image. Press Ctrl+V to paste.');
    }).catch(failure => { if (id === request) report(failure); }).finally(() => {
      if (id === request) { loading = false; draw(); }
    });
  });
  dialog.addEventListener('paste', event => {
    const item = Array.from(event.clipboardData?.items ?? []).find(i => i.type.startsWith('image/'));
    const blob = item?.getAsFile();
    if (!blob) return;
    event.preventDefault();
    if (!saving) void load(blob, true).catch(report);
  });
  color.addEventListener('input', () => { settings.color = color.value; draw(); });
  zoom.addEventListener('input', () => { settings.zoom = Number(zoom.value); draw(); });
  canvas.addEventListener('wheel', event => {
    event.preventDefault();
    if (saving) return;
    settings.zoom = Math.min(10, Math.max(1, settings.zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1)));
    zoom.value = String(settings.zoom);
    draw();
  }, { passive: false });
  let drag: { x: number; y: number } | undefined;
  canvas.addEventListener('pointerdown', event => {
    if (saving || event.button !== 0) return;
    canvas.setPointerCapture(event.pointerId);
    drag = { x: event.clientX, y: event.clientY };
  });
  canvas.addEventListener('pointermove', event => {
    if (!source || !drag) return;
    const rect = canvas.getBoundingClientRect();
    const factor = Math.min(source.naturalWidth, source.naturalHeight) / settings.zoom / rect.width;
    settings.centerX = Math.min(1, Math.max(0, settings.centerX - (event.clientX - drag.x) * factor / source.naturalWidth));
    settings.centerY = Math.min(1, Math.max(0, settings.centerY - (event.clientY - drag.y) * factor / source.naturalHeight));
    drag = { x: event.clientX, y: event.clientY };
    draw();
  });
  canvas.addEventListener('lostpointercapture', () => { drag = undefined; });
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  save.addEventListener('click', () => {
    if (saving || loading || !source) return;
    if (!canEdit()) { error.textContent = 'This character sheet is read-only.'; return; }
    if (!size.reportValidity()) return;
    settings.sizeTiles = Number(size.value);
    saving = true;
    drag = undefined;
    for (const control of [choose, paste, cancel, color, zoom, size]) control.disabled = true;
    draw();
    error.textContent = 'Saving token...';
    void tokenArtwork(actorId, { ...settings, ...(image ? { image } : {}) }).then(saved => {
      result = saved.artwork;
      dialog.close();
    }).catch(report).finally(() => {
      saving = false;
      for (const control of [choose, paste, cancel, color, zoom, size]) control.disabled = false;
      draw();
    });
  });
  dialog.append(el('h3', {}, 'Customize character token'),
    el('div', { class: 'row' }, el('label', {}, 'Border color ', color), el('label', {}, 'Size (tiles) ', size)),
    canvas, el('label', {}, 'Zoom ', zoom),
    el('p', { class: 'muted' }, 'Drag to pan; use the slider or wheel to zoom. Circular border is required. Saving updates this actor on all maps.'),
    el('div', { class: 'acts' }, choose, paste), file, error, el('div', { class: 'acts' }, cancel, save));
  document.body.append(dialog);
  dialog.showModal();
  draw();
  if (settings.source) {
    const initialRequest = request;
    void hostFetch(settings.source).then(async response => {
      if (!response.ok) throw new Error('Cannot load the character image.');
      const blob = await response.blob();
      if (request === initialRequest && dialog.isConnected) await load(blob, false);
    }).catch(report);
  }
  return new Promise(resolve => {
    dialog.addEventListener('close', () => {
      request++;
      dialog.remove();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
      resolve(result);
    }, { once: true });
  });
}
