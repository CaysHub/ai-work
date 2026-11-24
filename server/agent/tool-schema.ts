export const stringField = { type: 'string' };
export const schema = (properties: Record<string, object>, required: string[]) => ({
  type: 'object', properties, required, additionalProperties: false,
});
