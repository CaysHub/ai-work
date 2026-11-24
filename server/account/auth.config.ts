export const authConfig = {
  cookie: 'workbench_session', sessionLifetimeMs: 7 * 86_400_000, loginWindowMs: 60_000, loginMaxAttempts: 12,
  setupAddresses: ['127.0.0.1', '::1', '::ffff:127.0.0.1'],
  saltBytes: 16, passwordHashBytes: 64, sessionTokenBytes: 32,
};
