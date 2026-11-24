import { bytesPerMiB } from '../../shared/config.ts';

export const fileConfig = { directory: 'workspaces', pathLength: 320, textMaxMb: 2, listLimit: 500 };
export const maxTextFileBytes = fileConfig.textMaxMb * bytesPerMiB;
