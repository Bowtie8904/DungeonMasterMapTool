import { el } from './login';

function messageDialog(message: string, title: string, acceptLabel: string, cancellable: boolean): Promise<boolean> {
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
    dialog.append(el('h3', {}, title), el('p', {}, message),
      el('div', { class: 'acts' }, ...(cancellable ? [cancel] : []), accept));
    document.body.append(dialog);
    dialog.showModal();
    (cancellable ? cancel : accept).focus();
  });
}

export function confirmDialog(message: string, title = 'Confirm action', acceptLabel = 'Continue'): Promise<boolean> {
  return messageDialog(message, title, acceptLabel, true);
}

export function noticeDialog(message: string, title = 'Notice'): Promise<boolean> {
  return messageDialog(message, title, 'OK', false);
}

/** Themed single-line text prompt; `validate` returns an inline error or an empty string. */
export function promptDialog(title: string, label: string, acceptLabel: string, validate: (value: string) => string,
                             description = ''): Promise<string | null> {
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog', ariaLabel: title });
  const input = el('input', { type: 'text', ariaLabel: label, maxLength: 100 });
  const error = el('p', { class: 'error', role: 'alert' });
  const accept = el('button', { type: 'submit', class: 'primary' }, acceptLabel);
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const form = el('form', { method: 'dialog' }, el('label', {}, label, input), error,
    el('div', { class: 'acts' }, cancel, accept));
  return new Promise(resolve => {
    let result: string | null = null;
    form.addEventListener('submit', event => {
      event.preventDefault();
      const problem = validate(input.value);
      error.textContent = problem;
      if (problem) { input.focus(); return; }
      result = input.value.trim();
      dialog.close();
    });
    cancel.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
      dialog.remove();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
      resolve(result);
    }, { once: true });
    dialog.append(el('h3', {}, title), ...(description ? [el('p', {}, description)] : []), form);
    document.body.append(dialog);
    dialog.showModal();
    input.focus();
  });
}
