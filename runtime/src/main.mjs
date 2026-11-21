// First: read-access and the vendored runtime call AbortSignal.any (Chrome 116+) and
// AbortSignal.timeout (Chrome 103+); the bundles target Chrome 100.
import '../vendor/cloud-runtime/src/runtime/abort-signal-any.ts';
import './abort-signal-timeout.mjs';
import {contractBridge} from './contract-bridge.mjs';
import '../vendor/cloud-runtime/src/runtime/index.ts';
import { createDispatcher, HOST_PROTOCOL, CORE_CAPABILITIES } from './dispatch.mjs';
const emit = message => globalThis.webkit.messageHandlers.dataRuntime.postMessage(message);
const runtime=contractBridge(globalThis.dataRuntime,emit);
globalThis.androidDispatch = createDispatcher(runtime, emit);
emit({type:'hostReady', protocol:HOST_PROTOCOL, capabilities:CORE_CAPABILITIES});
