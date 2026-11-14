/**
 * Node 25 的 test runner 把位置参数当 glob，`node --test tests/` 不会展开目录；
 * 这里提供目录入口（tests/package.json main），让该命令也能跑全部用例。
 * 推荐命令仍是 `node --test`（默认递归发现所有 .test.js）。
 */
import './auth.test.js'
import './upstream.test.js'
import './stream.test.js'
import './files.test.js'
import './integration.test.js'
import './plugin.test.js'
import './proxy-trust.test.js'
import './ws.test.js'
