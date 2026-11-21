// The dispatcher calls an adapter as `(...nativeArgs, signal)`, so an omitted optional argument
// leaves the AbortSignal in its position. Only a string is a machine comparison scope.
export const withDiffScope = (params, scope) =>
  typeof scope === 'string' ? { ...params, scope } : params;
