/**
 * `AbortSignal.timeout` for Android System WebView before 103 (the bundles target chrome100;
 * iOS 16.4 already has it, so the vendored runtime ships no fallback). As the DOM standard: the
 * signal aborts with a `TimeoutError` DOMException after `ms`; a negative, fractional or
 * non-finite delay is a TypeError. Installed only where the platform lacks it.
 */
export function abortSignalTimeout(ms) {
 if(typeof ms!=='number' || !Number.isSafeInteger(ms) || ms<0)throw new TypeError('AbortSignal.timeout: delay must be a non-negative integer');
 const controller=new AbortController();
 setTimeout(()=>controller.abort(new DOMException('signal timed out','TimeoutError')),ms);
 return controller.signal;
}

export function installAbortSignalTimeout(target=AbortSignal) {
 if(typeof target.timeout==='function')return false;
 Object.defineProperty(target,'timeout',{value:abortSignalTimeout,writable:true,configurable:true});
 return true;
}

installAbortSignalTimeout();
