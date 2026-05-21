import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const css = readFileSync(new URL('./styles.css', import.meta.url), 'utf8')
const app = readFileSync(new URL('./App.vue', import.meta.url), 'utf8')
const viteConfig = readFileSync(new URL('../vite.config.js', import.meta.url), 'utf8')

test('result panes keep independent scroll regions while the page can still scroll', () => {
  assert.match(css, /\.split-workspace\s*\{[^}]*height:\s*calc\(100vh -/s)
  assert.match(css, /\.source-pane,[\s\S]*?\.ocr-pane\s*\{[^}]*overflow:\s*hidden/s)
  assert.match(css, /\.document-canvas,[\s\S]*?\.json-panel\s*\{[^}]*overflow-y:\s*auto/s)
  assert.doesNotMatch(css, /body\s*\{[^}]*overflow:\s*hidden/s)
})

test('field value characters render in black and review marks avoid underlines', () => {
  assert.match(css, /\.field-value-text span\s*\{[^}]*color:\s*var\(--ink\)/s)
  assert.match(css, /\.field-value-text \.char-review\s*\{[^}]*color:\s*var\(--danger\)/s)
  assert.doesNotMatch(css, /\.field-value-text \.char-review\s*\{[^}]*text-decoration/s)
})

test('source page snapshot does not render character review overlays', () => {
  assert.doesNotMatch(app, /issue-overlay/)
  assert.doesNotMatch(app, /issue-marker/)
  assert.doesNotMatch(css, /\.issue-overlay/)
  assert.doesNotMatch(css, /\.issue-marker/)
})

test('field extraction view does not render OCR model comparison status', () => {
  assert.doesNotMatch(app, /ocrStatusText/)
  assert.doesNotMatch(app, /localOcrStatusText/)
  assert.doesNotMatch(app, /OCR模型|OCR妯/)
})

test('frontend fallback model labels use local and cloud-native display names', () => {
  assert.match(app, /label:\s*LOCAL_MODEL_LABEL/)
  assert.match(app, /normalizeModelOptions\(models\)/)
  assert.doesNotMatch(app, /Qwen3\.6-35B-A3B 视觉结构化/)
  assert.doesNotMatch(app, /Qwen3\.6-35B-A3B（官方原生）/)
})

test('page header uses OCR demo copy', () => {
  assert.match(app, />Full-page OCR Demo</)
  assert.match(app, />识别材料，右侧分页展示结构化识别结果。</)
  assert.doesNotMatch(app, />Full-page LLM Demo</)
  assert.doesNotMatch(app, />PDF 或图片按整页送入多模态大模型，右侧展示自动生成的结构化 JSON。</)
})

test('primary upload action does not mention LLM', () => {
  assert.match(app, />\s*开始识别\s*</)
  assert.doesNotMatch(app, />\s*开始 LLM 识别\s*</)
})

test('demo result state is not reset by development hot updates', () => {
  assert.match(viteConfig, /hmr:\s*false/)
  assert.match(app, /__AIFULLFORMRECONIZATION_STATE__/)
  assert.doesNotMatch(app, /sessionStorage/)
})
