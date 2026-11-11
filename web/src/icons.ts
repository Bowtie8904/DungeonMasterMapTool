import { el } from './login';

const paths: Record<string, string> = {
  add: 'M12 5v14M5 12h14',
  navigate: 'M5 3 19 12 12 13 9 20Z',
  ping: 'M12 2v5M12 17v5M2 12h5M17 12h5M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0ZM14 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0Z',
  draw: 'm10 14 9-11 3 3-11 9M10 14c-6-2-3 6-8 6 6 3 11-2 8-6Z',
  pen: 'm4 15 12-12 5 5L9 20l-6 1ZM14 5l5 5M4 15l5 5',
  line: 'M4 20 20 4M3 18l3 3M18 3l3 3',
  circle: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  rect: 'M4 4h16v16H4Z',
  measure: 'm3 16 13-13 5 5L8 21ZM12 7l2 2M8 11l2 2M4 15l2 2',
  radius: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 12h9M12 10v4',
  cone: 'M3 12 20 3a19 19 0 0 1 0 18Z',
  chat: 'M4 4h16v12H9l-5 5ZM8 8h8M8 12h5',
  cards: 'M3 4h7v16H3ZM14 4h7v16h-7Z',
  list: 'M3 5h2M8 5h13M3 12h2M8 12h13M3 19h2M8 19h13',
  transfer: 'M4 8h15m-4-4 4 4-4 4M20 16H5m4-4-4 4 4 4',
  grab: 'M8 13V6a2 2 0 0 1 4 0v5-2a2 2 0 0 1 4 0v3a2 2 0 0 1 4 0v4c0 4-2 6-6 6h-1c-2 0-3-1-4-2l-5-6a2 2 0 0 1 3-2l2 2',
  actors: 'M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM3 21v-3a7 7 0 0 1 14 0v3M17 4a4 4 0 0 1 0 8M20 21v-4',
  notes: 'M5 3h10l4 4v14H5ZM14 3v5h5M8 12h8M8 16h8',
  settings: 'M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  close: 'M6 6l12 12M18 6 6 18',
  sheet: 'M6 3h9l3 3v15H6zM9 10h6M9 14h6M9 18h4',
  compendium: 'M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2zM4 20a2 2 0 0 0 2 2h13M9 7h6',
  files: 'M3 6a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2ZM7 17l3-4 2 2 2-3 3 5',
  delete: 'M3 6h18M9 6V3h6v3M6 6v15h12V6M10 10v7M14 10v7',
  shield: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6ZM9 12h6',
  star: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3l-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z',
};

export function iconButton(icon: string, label: string, action: () => void): HTMLButtonElement {
  const button = el('button', { class: 'icon-button', type: 'button', title: label });
  button.setAttribute('aria-label', label);
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  path.setAttribute('d', paths[icon] ?? paths.navigate);
  svg.append(path);
  button.append(svg);
  button.addEventListener('click', action);
  return button;
}
