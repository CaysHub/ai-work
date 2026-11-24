export const botConfig = {
  enabled: false, targetLimit: 500, targetIdLength: 250, employeeIdLength: 200, credentialLength: 4_000,
  activityLimit: 40, workerBatchSize: 20, pendingMaxAgeMs: 600_000, historyClockSkewMs: 1_000, historyVerificationPages: 4,
  platformErrorLength: 300, appReplyLength: 3_000,
  retryMs: 15_000, historyLagMs: 2_000, discoveryIntervalMs: 60_000, historyOverlapMs: 15_000,
  historyBacklogMs: 6 * 86_400_000, discoveredGroupLifetimeMs: 15 * 60_000, pollingConcurrency: 5,
  feishuTokenRefreshMs: 3_600_000, dingTokenRefreshSeconds: 120, dingRobotTextLength: 1_500, wecomRateLimitCode: 846607,
};
