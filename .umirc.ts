import { defineConfig } from 'umi';
import { existsSync, readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { httpPaths, serverDefaults } from './shared/config.ts';
import { appTitle, themeColor } from './src/workspace/theme.config.ts';

const config = existsSync(serverDefaults.envFile) ? parseEnv(readFileSync(serverDefaults.envFile, 'utf8')) : {};
const bind = config.WORKBENCH_HOST ?? serverDefaults.host;
const proxyHost = bind === '0.0.0.0' ? serverDefaults.host : bind === '::' ? '[::1]' : bind.includes(':') ? '[' + bind + ']' : bind;
const target = 'http://' + proxyHost + ':' + (config.WORKBENCH_PORT ?? serverDefaults.port);

export default defineConfig({
  title: appTitle,
  metas: [{ name: 'theme-color', content: themeColor }],
  npmClient: 'npm',
  mfsu: false,
  hash: true,
  proxy: { [httpPaths.api]: { target, changeOrigin: true }, [httpPaths.integrations]: { target, changeOrigin: true } },
});
