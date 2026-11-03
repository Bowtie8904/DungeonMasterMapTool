import { el } from './login';

const paths: Record<string, string> = {
  navigate: 'M5 3 19 12 12 13 9 20Z',
  draw: 'm10 14 9-11 3 3-11 9M10 14c-6-2-3 6-8 6 6 3 11-2 8-6Z',
  pen: 'm4 15 12-12 5 5L9 20l-6 1ZM14 5l5 5M4 15l5 5',
  line: 'M4 20 20 4M3 18l3 3M18 3l3 3',
  circle: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  rect: 'M4 4h16v16H4Z',
  measure: 'm3 16 13-13 5 5L8 21ZM12 7l2 2M8 11l2 2M4 15l2 2',
  radius: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 12h9M12 10v4',
  cone: 'M3 12 20 3a19 19 0 0 1 0 18Z',
  chat: 'M4 4h16v12H9l-5 5ZM8 8h8M8 12h5',
  grab: 'M8 13V6a2 2 0 0 1 4 0v5-2a2 2 0 0 1 4 0v3a2 2 0 0 1 4 0v4c0 4-2 6-6 6h-1c-2 0-3-1-4-2l-5-6a2 2 0 0 1 3-2l2 2',
  actors: 'M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM3 21v-3a7 7 0 0 1 14 0v3M17 4a4 4 0 0 1 0 8M20 21v-4',
  settings: 'M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6',
  close: 'M6 6l12 12M18 6 6 18',
  sheet: 'M6 3h9l3 3v15H6zM9 10h6M9 14h6M9 18h4',
  compendium: 'M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2zM4 20a2 2 0 0 0 2 2h13M9 7h6',
  delete: 'M3 6h18M9 6V3h6v3M6 6v15h12V6M10 10v7M14 10v7',
  shield: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6ZM9 12h6',
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
