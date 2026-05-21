export const LOCAL_MODEL_LABEL = '本地模型'
export const CLOUD_NATIVE_MODEL_LABEL = '云原生模型'

const MODEL_LABELS_BY_ID = new Map([
  ['local-qwen3.6-35b-a3b', LOCAL_MODEL_LABEL],
  ['dashscope-qwen3.6-35b-a3b', CLOUD_NATIVE_MODEL_LABEL]
])

const LEGACY_LABELS = new Map([
  ['Qwen3.6-35B-A3B 视觉结构化', LOCAL_MODEL_LABEL],
  ['Qwen3.6-35B-A3B（官方原生）', CLOUD_NATIVE_MODEL_LABEL],
  ['Qwen3.6-35B-A3B multimodal structured extraction', LOCAL_MODEL_LABEL],
  ['qwen3.6-35b-a3b multimodal structured extraction', CLOUD_NATIVE_MODEL_LABEL],
  ['Qwen3.6-35B-A3B', LOCAL_MODEL_LABEL],
  ['qwen3.6-35b-a3b', CLOUD_NATIVE_MODEL_LABEL]
])

export function modelDisplayLabel(model, fallback = LOCAL_MODEL_LABEL) {
  if (!model) return fallback
  if (typeof model === 'string') {
    return LEGACY_LABELS.get(model) || model || fallback
  }
  if (MODEL_LABELS_BY_ID.has(model.id)) {
    return MODEL_LABELS_BY_ID.get(model.id)
  }
  const label = String(model.label || '').trim()
  return LEGACY_LABELS.get(label) || label || fallback
}

export function normalizeModelOptions(models = []) {
  return models.map((model) => ({
    ...model,
    label: modelDisplayLabel(model, model?.label || '')
  }))
}

export function extractionModeDisplayLabel(response) {
  const rawLabel = response?.engineStatus?.extractionMode || response?.model || ''
  return modelDisplayLabel(rawLabel, rawLabel || LOCAL_MODEL_LABEL)
}
