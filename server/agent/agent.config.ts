export const agentConfig = {
  provider: 'workbench', storageDirectory: 'pi', deepseekHost: 'api.deepseek.com',
  toolPreviewLength: 12_000, toolResultLength: 100_000, toolHistoryLimit: 80, progressHistoryLimit: 80,
  cloudResultTitleLength: 80, cloudResultNameSuffixLength: 48,
  triageContextLength: 800, triageContextRuns: 5, triageTimeoutMs: 30_000, triageMaxTokens: 256, triageReasonLength: 240,
  streamTimeoutMs: 120_000, maxRetries: 2, maxTurns: 20, observationMaxEntries: 80,
  linkedDocumentLength: 16_000, failureMessageLength: 360, queuedBatchSize: 50,
};
export const decisionTool = 'wecom_reply_decision';
export const triageTool = 'im_reply_triage';
