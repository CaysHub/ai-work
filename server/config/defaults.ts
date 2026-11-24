export const defaults = {
  dataDir: './data',
  documentMaxMb: 25,
  documentMaxSupportedMb: 100,
  dwsSubdirectory: 'dws',
  model: {
    baseUrl: 'https://api.deepseek.com',
    id: 'deepseek-v4-flash',
    contextWindow: 131_072,
    maxTokens: 8_192,
  },
};
