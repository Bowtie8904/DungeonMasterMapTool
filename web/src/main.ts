import './style.css';
import './daggerheart/sheet.css';
import { App } from './app';
import { showLogin } from './login';
import { startSheet } from './sheet';
import { startToolPopout } from './compendium-popout';
import { startDmCompendium } from './dm-compendium';

const root = document.getElementById('app')!;
const sheetMatch = /^\/sheet\/([^/]+)\/?$/.exec(location.pathname);
const params = new URLSearchParams(location.search);
const popout = params.get('popout');

function startMain(): void {
  const app = new App(root, () => void showLogin(root, startMain));
  app.start();
}

if (params.get('dm-compendium') === '1') {
  startDmCompendium(root);
} else if (popout === 'note' || popout === 'compendium') {
  startToolPopout(root, popout, params.get('path') ?? '');
} else if (sheetMatch) {
  void startSheet(root, decodeURIComponent(sheetMatch[1]));
} else {
  // The session cookie is HttpOnly: try the socket first, the login screen appears when it is refused.
  startMain();
}
