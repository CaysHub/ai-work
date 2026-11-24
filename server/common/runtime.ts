export const now = () => Date.now();
export function parse<T = unknown>(value: string): T {
  return JSON.parse(value) as T;
}
export function hasCode(error: unknown, code: string) {
  return error instanceof Error && 'code' in error && error.code === code;
}
export function httpError(status: number, message: string) {
  return Object.assign(new Error(message), { status });
}
