import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  extractionModeDisplayLabel,
  modelDisplayLabel,
  normalizeModelOptions
} from './modelDisplay.js'

test('modelDisplayLabel maps configured model ids to concise display names', () => {
  assert.equal(modelDisplayLabel({ id: 'local-qwen3.6-35b-a3b', label: 'Qwen3.6-35B-A3B 视觉结构化' }), '本地模型')
  assert.equal(modelDisplayLabel({ id: 'dashscope-qwen3.6-35b-a3b', label: 'Qwen3.6-35B-A3B（官方原生）' }), '云原生模型')
})

test('normalizeModelOptions replaces legacy backend labels before rendering', () => {
  assert.deepEqual(
    normalizeModelOptions([
      { id: 'local-qwen3.6-35b-a3b', label: 'Qwen3.6-35B-A3B 视觉结构化' },
      { id: 'dashscope-qwen3.6-35b-a3b', label: 'Qwen3.6-35B-A3B（官方原生）' }
    ]).map((model) => model.label),
    ['本地模型', '云原生模型']
  )
})

test('normalizeModelOptions hides qwen3.6-plus from frontend selectors', () => {
  assert.deepEqual(
    normalizeModelOptions([
      { id: 'local-qwen3.6-35b-a3b', label: 'Local model' },
      { id: 'qwen3.6-plus', label: 'qwen3.6-plus' },
      { id: 'dashscope-qwen3.6-35b-a3b', label: 'Cloud native model' }
    ]).map((model) => model.id),
    ['local-qwen3.6-35b-a3b', 'dashscope-qwen3.6-35b-a3b']
  )
})

test('extractionModeDisplayLabel maps legacy result status text', () => {
  assert.equal(
    extractionModeDisplayLabel({ engineStatus: { extractionMode: 'Qwen3.6-35B-A3B multimodal structured extraction' } }),
    '本地模型'
  )
  assert.equal(
    extractionModeDisplayLabel({ engineStatus: { extractionMode: 'qwen3.6-35b-a3b multimodal structured extraction' } }),
    '云原生模型'
  )
})
