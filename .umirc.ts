import { defineConfig } from 'umi';
import { existsSync, readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';

const config = existsSync('.env') ? parseEnv(readFileSync('.env', 'utf8')) : {};
const bind = config.WORKBENCH_HOST ?? '127.0.0.1';
const proxyHost = bind === '0.0.0.0' ? '127.0.0.1' : bind === '::' ? '[::1]' : bind.includes(':') ? '[' + bind + ']' : bind;
const target = 'http://' + proxyHost + ':' + (config.WORKBENCH_PORT ?? '3400');

export default defineConfig({
  title: '企业 AI 工作台',
  metas: [{ name: 'theme-color', content: '#15352d' }],
  npmClient: 'npm',
  mfsu: false,
  hash: true,
  proxy: { '/api': { target, changeOrigin: true }, '/integrations': { target, changeOrigin: true } },
});
