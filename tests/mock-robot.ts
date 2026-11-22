import { EventEmitter } from 'node:events';
import { config } from '../server/config.ts';

const origin = new URL(config.model.baseUrl).origin;
type Event = Record<string, unknown>;
function stream(platform: string, receive: (event: Event) => void) {
  const controller = new AbortController();
  let polling = false;
  const timer = setInterval(async () => {
    if (polling) return;
    polling = true;
    try {
      const response = await fetch(origin + '/robot/' + platform, { signal: controller.signal });
      if (response.ok) for (const event of await response.json() as Event[]) receive(event);
    } catch {
      // The test server may close before the client during teardown.
    } finally { polling = false; }
  }, 120);
  timer.unref();
  return () => { clearInterval(timer); controller.abort(); };
}

export class EventDispatcher {
  handler?: (event: Event) => void;
  register(handlers: Record<string, (event: Event) => void>) {
    this.handler = handlers['im.message.receive_v1'];
    return this;
  }
}

export class WSClient {
  private stop?: () => void;
  start({ eventDispatcher }: { eventDispatcher: EventDispatcher }) {
    this.stop = stream('feishu', (event) => eventDispatcher.handler?.(event));
  }
  close() { this.stop?.(); }
}

class WecomClient extends EventEmitter {
  private stop?: () => void;
  connect() {
    this.stop = stream('wecom', (body) => this.emit('message.text', { body }));
    this.emit('authenticated');
  }
  disconnect() { this.stop?.(); }
  async sendMessage(target: string, body: unknown, passive = false) {
    const response = await fetch(origin + '/robot/wecom', { method: 'POST', body: JSON.stringify({ target, body, passive }) });
    if (!response.ok) throw new Error('Mock robot send failed');
    const frame = await response.json();
    if (frame.errcode) throw frame;
    return frame;
  }
  replyStream(frame: { body: { from: { userid: string } } }, _id: string, content: string) {
    return this.sendMessage(frame.body.from.userid, { markdown: { content } }, true);
  }
}

export default { WSClient: WecomClient };
export const TOPIC_ROBOT = '/v1.0/im/bot/messages/get';

export class DWClient {
  private stop?: () => void;
  private handler?: (frame: { data: string; headers: { messageId: string } }) => void;
  registerCallbackListener(_topic: string, handler: NonNullable<DWClient['handler']>) { this.handler = handler; }
  connect() {
    this.stop = stream('dingtalk', (event) => this.handler?.({ data: JSON.stringify(event), headers: { messageId: String(event.msgId) } }));
  }
  disconnect() { this.stop?.(); }
  socketCallBackResponse() {}
}
