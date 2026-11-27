import { el } from './login';

function messageDialog(message: string | HTMLElement, title: string, acceptLabel: string, cancellable: boolean): Promise<boolean> {
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog', ariaLabel: title });
  const accept = el('button', { type: 'button', class: 'primary' }, acceptLabel);
  const cancel = el('button', { type: 'button' }, 'Cancel');
  return new Promise(resolve => {
    let accepted = false;
    accept.addEventListener('click', () => { accepted = true; dialog.close(); });
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
      resolve(accepted);
    }, { once: true });
    dialog.append(el('h3', {}, title), typeof message === 'string' ? el('p', {}, message) : message,
      el('div', { class: 'acts' }, ...(cancellable ? [cancel] : []), accept));
    document.body.append(dialog);
    dialog.showModal();
    (cancellable ? cancel : accept).focus();
  });
}

export function confirmDialog(message: string, title = 'Confirm action', acceptLabel = 'Continue'): Promise<boolean> {
  return messageDialog(message, title, acceptLabel, true);
}

export function noticeDialog(message: string | HTMLElement, title = 'Notice'): Promise<boolean> {
  return messageDialog(message, title, 'OK', false);
}
