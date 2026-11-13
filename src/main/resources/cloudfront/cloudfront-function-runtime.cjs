'use strict';

// This harness runs once in a disposable, resource-limited container. node:vm is
// an evaluation/context tool, not the isolation boundary.
const vm = require('node:vm');

const payload = JSON.parse(Buffer.from(process.argv.slice(1).join(''), 'base64').toString('utf8'));
if (typeof payload.code !== 'string' || typeof payload.event !== 'string') {
  process.exitCode = 2;
} else {
  const context = vm.createContext(Object.create(null), {
    codeGeneration: {strings: false, wasm: false},
    microtaskMode: 'afterEvaluate'
  });
  const prefix = `
    (() => {
      const stringify = JSON.stringify;
      const parse = JSON.parse;
      const text = String;
      const logLines = [];
      let logBytes = 0;
      let truncated = false;
      const maxLogBytes = 8192;
      const marker = '[logs truncated]';
      function utf8Size(value) {
        let bytes = 0;
        for (let i = 0; i < value.length; i++) {
          const code = value.charCodeAt(i);
          if (code < 128) bytes++;
          else if (code < 2048) bytes += 2;
          else if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length
                   && value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) {
            bytes += 4;
            i++;
          } else bytes += 3;
        }
        return bytes;
      }
      function boundedText(value, maximum) {
        const rendered = text(value);
        let end = 0;
        let bytes = 0;
        while (end < rendered.length) {
          const code = rendered.charCodeAt(end);
          let width = 1;
          let size = code < 128 ? 1 : code < 2048 ? 2 : 3;
          if (code >= 0xd800 && code <= 0xdbff && end + 1 < rendered.length
              && rendered.charCodeAt(end + 1) >= 0xdc00 && rendered.charCodeAt(end + 1) <= 0xdfff) {
            width = 2;
            size = 4;
          }
          if (bytes + size > maximum) break;
          bytes += size;
          end += width;
        }
        return rendered.slice(0, end);
      }
      function log(...values) {
        if (truncated) return;
        const parts = [];
        for (const value of values) {
          parts.push(typeof value === 'string' ? value : text(value));
        }
        const line = parts.join(' ');
        const bytes = utf8Size(line);
        if (logLines.length >= 99 || logBytes + bytes > maxLogBytes - marker.length) {
          logLines.push(marker);
          logBytes += marker.length;
          truncated = true;
          return;
        }
        logLines.push(line);
        logBytes += bytes;
      }
      Object.defineProperty(globalThis, 'console', {
        value: Object.freeze({log}), writable: false, configurable: false
      });
      const event = parse(${JSON.stringify(payload.event)});
      let output = null;
      let error = null;
      try {
        const returned = (function(__flociInput) {
  `;
  const suffix = `
          if (typeof handler !== 'function') throw new Error('A handler function is required.');
          return handler(__flociInput);
        })(event);
        if (returned && typeof returned.then === 'function') {
          throw new Error('Promise and async handlers are not supported by this local runtime.');
        }
        if (returned === null || typeof returned !== 'object' || Array.isArray(returned)) {
          throw new Error('The handler must return a serializable request or response object.');
        }
        const response = Object.prototype.hasOwnProperty.call(returned, 'statusCode')
          || (event.context && event.context.eventType === 'viewer-response');
        output = stringify(response ? {response: returned} : {request: returned});
        if (typeof output !== 'string' || utf8Size(output) > 40960) {
          throw new Error('The local function output limit is 40960 UTF-8 bytes.');
        }
      } catch (failure) {
        output = null;
        try {
          error = boundedText(failure && failure.name ? failure.name : 'Error', 128)
            + ': ' + boundedText(failure && failure.message !== undefined ? failure.message : failure, 2048);
        } catch (formatFailure) {
          error = 'Function error could not be formatted.';
        }
      }
      return stringify({protocol: 1, output, error, logs: logLines});
    })()
  `;
  let result;
  try {
    // Compilation is additionally bounded by the parent container deadline.
    new vm.Script(payload.code, {filename: 'cloudfront-function.js'});
    const script = new vm.Script(prefix + '\n' + payload.code + '\n' + suffix, {
      filename: 'cloudfront-function.js'
    });
    result = script.runInContext(context, {timeout: 2000});
    if (typeof result !== 'string' || Buffer.byteLength(result, 'utf8') > 131072) {
      throw new Error('Invalid result.');
    }
  } catch (failure) {
    // Never read properties of an object thrown across the VM boundary.
    result = JSON.stringify({
      protocol: 1, output: null,
      error: 'Function compilation or evaluation failed, or exceeded the local execution limit.',
      logs: []
    });
  }
  process.stdout.write(result + '\n');
}
