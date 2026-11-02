import { Observable } from 'rxjs';

/**
 * Follows a server-sent event stream with a bearer token. The browser's EventSource cannot send
 * an Authorization header, so this reads the stream with fetch. It reconnects after a drop, waiting
 * longer each time (1 s up to 30 s), and gets a fresh token for each connection. Nothing runs on
 * the server side rendering pass.
 */
export function liveStream<T>(url: string, token: () => Promise<string | null>): Observable<T> {
  return new Observable<T>((subscriber) => {
    if (typeof window === 'undefined' || typeof fetch === 'undefined') {
      return undefined;
    }
    let stopped = false;
    let controller: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let failures = 0;

    const connect = async (): Promise<void> => {
      if (stopped) return;
      controller = new AbortController();
      try {
        const bearer = await token();
        const response = await fetch(url, {
          headers: {
            Accept: 'text/event-stream',
            ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
          },
          signal: controller.signal,
          cache: 'no-store',
        });
        if (response.status === 401 || response.status === 403 || response.status === 404) {
          subscriber.error(new Error(`Live updates refused (${response.status})`));
          return;
        }
        if (!response.ok || !response.body)
          throw new Error(`Live updates failed (${response.status})`);
        failures = 0;
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;
          let end: number;
          while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
            const block = buffer.slice(0, end);
            buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '');
            const data = block
              .split(/\r?\n/)
              .filter((line) => line.startsWith('data:'))
              .map((line) => line.slice(5).trimStart())
              .join('\n');
            if (data) {
              try {
                subscriber.next(JSON.parse(data) as T);
              } catch {
                // not JSON: ignore
              }
            }
          }
        }
      } catch {
        if (stopped) return;
        failures++;
      }
      if (!stopped) {
        const wait = Math.min(30_000, 1000 * 2 ** Math.min(failures, 5));
        timer = setTimeout(() => void connect(), wait);
      }
    };

    void connect();
    return () => {
      stopped = true;
      controller?.abort();
      if (timer) clearTimeout(timer);
    };
  });
}
