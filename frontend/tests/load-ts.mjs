import { readFileSync } from 'node:fs'
import ts from 'typescript'

// 使用已有 TypeScript 依赖转译纯业务模块，不新增测试依赖或向源码目录写产物。
const moduleUrls = new Map()
function moduleUrl(url) {
  if (moduleUrls.has(url.href)) return moduleUrls.get(url.href)
  let code = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText
  code = code.replace(/from ['"](\.\/[^'"]+)['"]/g, (_, path) => `from ${JSON.stringify(moduleUrl(new URL(`${path}.ts`, url)))}`)
  const result = `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
  moduleUrls.set(url.href, result)
  return result
}
export const loadTs = name => import(moduleUrl(new URL(`../src/${name}.ts`, import.meta.url)))
