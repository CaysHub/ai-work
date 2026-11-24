import { bytesPerMiB } from '../../shared/config.ts';

export const documentConfig = {
  directory: 'documents', fileNameLength: 240, largestPage: 100_000,
  previewTextLength: 80_000, previewXmlMaxBytes: 4 * bytesPerMiB,
  previewSlides: 12, previewSheets: 5, previewRows: 200, previewColumns: 40,
  wordTextLength: 200_000, wordTitleLength: 240,
  wecomRangeLimit: 3,
  cloudTitleLength: 250, cloudTextLength: 100_000, cloudLinkLength: 1_000, sourceLinkLimit: 2,
  wecomReadAttempts: 8, wecomReadRetryMs: 1_000,
  searchKeywords: 5, searchKeywordLength: 100, dingSearchResults: 20, feishuSearchResults: 15,
  feishuSearchPageSize: 100, feishuSearchPages: 10, mcpToolListPages: 10,
  dwsTimeoutMs: 45_000, dwsStderrLength: 16_000, dwsOutputLength: 2_000_000,
};
