import { openAgent } from './agent/worker.ts';
import { workerIntervals } from './app/app.config.ts';
import { createApp } from './app/app.ts';
import { openBots } from './bots/worker.ts';
import { config } from './config/env.ts';
import { tickSchedules } from './schedules/schedules.ts';

const agent = openAgent();
const app = createApp(agent.cancel);
const bots = openBots();
const safe = (operation: () => void) => { try { operation(); } catch (error) { console.error(error); } };
const timers = [
  setInterval(() => safe(agent.tick), workerIntervals.agent),
  setInterval(() => safe(tickSchedules), workerIntervals.schedules),
  setInterval(() => safe(bots.tick), workerIntervals.bots),
];
const server = app.listen(config.port, config.host, () => {
  const address = server.address();
  if (address && typeof address !== 'string') console.log('AI 工作台已启动 http://' + config.host + ':' + address.port);
});
let closing = false;
async function close() {
  if (closing) return;
  closing = true;
  timers.forEach(clearInterval);
  bots.close();
  server.close();
  await agent.close();
}
process.on('SIGINT', close);
process.on('SIGTERM', close);
