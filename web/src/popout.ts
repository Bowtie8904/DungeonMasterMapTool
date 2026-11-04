export function openPopout(url: string, width: number, height: number, name = '_blank'): Window | null {
  const popup = window.open(url, name, `popup,width=${width},height=${height}`);
  return popup && !popup.closed ? popup : null;
}
