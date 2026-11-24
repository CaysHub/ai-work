import { replyReferenceLength } from '../../shared/config.ts';

export const reviewWindow = 24 * 60 * 60 * 1_000;

export const replyConfig = {
  draftLength: 2_000, decisionReasonLength: 2_000, attachmentErrorLength: 200, promptPreviewLength: 140,
  noticeMaxBytes: 2_000, dingNoticeMaxCharacters: 1_500, searchBotIdLength: 100, largestPage: 100_000,
  commandInstructionLength: 4_000, commandPromptPreviewLength: 75, commandSummaryLimit: 8, commandResultLimit: 10,
  commandReplyPreviewLength: 1_100, receiptClockSkewMs: 999, receiptHistoryPages: 4,
  wecomPartMaxBytes: 2_000, wecomPartLabelBytes: 24,
  attachmentMaxMb: 5, attachmentWindowMs: 10 * 60 * 1_000, attachmentMediaIdLength: 256, attachmentLimit: 10,
};

export const ownerCommandPattern = new RegExp(
  '^(接管|忽略|重试)\\s+(最新|[0-9a-f-]{' + replyReferenceLength + ',36})(?:\\s+([\\s\\S]+))?$', 'i',
);
