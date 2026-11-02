import './style.css';
import './daggerheart/sheet.css';
import { App } from './app';
import { showLogin } from './login';
import { startSheet } from './sheet';

const root = document.getElementById('app')!;
const sheetMatch = /^\/sheet\/([^/]+)\/?$/.exec(location.pathname);

function startMain(): void {
  const app = new App(root, () => void showLogin(root, startMain));
  app.start();
}

if (sheetMatch) {
  void startSheet(root, decodeURIComponent(sheetMatch[1]));
} else {
  // The session cookie is HttpOnly: try the socket first, the login screen appears when it is refused.
  startMain();
}
