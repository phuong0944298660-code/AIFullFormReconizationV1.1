<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  visibleOcrPages,
  responseJsonPreview,
  structuredJsonPreview,
  pageStructuredFieldCount,
  structuredFieldRows,
  pageFieldConclusion,
  documentFieldConclusion,
  cropPlaceholderText
} from './ocrPresentation.js'
import { pageProgressItems, recognitionProgressState } from './progressState.js'

const apiBase = import.meta.env.VITE_API_BASE || ''
const runtimeState = getRuntimeState()

const file = ref(null)
const fileInput = ref(null)
const response = ref(runtimeState.response || null)
const activePage = ref(runtimeState.activePage || 1)
const resultTab = ref(runtimeState.resultTab || 'fields')
const loading = ref(runtimeState.loading || false)
const error = ref(runtimeState.error || '')
const progress = ref(runtimeState.progress || 0)
const progressStage = ref(runtimeState.progressStage || '')
const progressDetail = ref(runtimeState.progressDetail || '')
const jobStatus = ref(runtimeState.jobStatus || null)
const activeJobId = ref(runtimeState.activeJobId || '')
const modelOptions = ref(runtimeState.modelOptions || [])
const selectedModelId = ref(runtimeState.selectedModelId || '')
let activeRunId = 0

const pages = computed(() => response.value?.pages || [])
const documentPages = computed(() => visibleOcrPages(pages.value))
const currentPage = computed(() => {
  return pages.value.find((page) => page.page === activePage.value) || pages.value[0] || null
})
const currentPageIndex = computed(() => {
  const index = pages.value.findIndex((page) => page.page === activePage.value)
  return index >= 0 ? index + 1 : 0
})
const totalVisibleLineCount = computed(() => {
  return documentPages.value.reduce((total, page) => total + page.visibleLines.length, 0)
})
const totalHighlightedLineCount = computed(() => {
  return documentPages.value.reduce((total, page) => total + page.highlightedLineCount, 0)
})
const totalFilteredLineCount = computed(() => {
  return documentPages.value.reduce((total, page) => total + page.filteredLineCount, 0)
})
const jsonPreview = computed(() => responseJsonPreview(response.value))
const structuredPreview = computed(() => structuredJsonPreview(response.value))
const currentPageFieldRows = computed(() => {
  if (!currentPage.value) return []
  return structuredFieldRows(response.value, currentPage.value.page)
})
const currentPageConclusion = computed(() => pageFieldConclusion(currentPageFieldRows.value))
const globalFieldConclusion = computed(() => documentFieldConclusion(response.value))
const selectedModel = computed(() => {
  return modelOptions.value.find((model) => model.id === selectedModelId.value) || modelOptions.value[0] || null
})
const selectedModelLabel = computed(() => selectedModel.value?.label || 'Qwen3.6-35B-A3B 视觉结构化')
const selectedModelUnavailableReason = computed(() => {
  if (!selectedModel.value || selectedModel.value.available !== false) return ''
  return selectedModel.value.unavailableReason || '该模型未配置 API Key'
})
const currentPageFieldSections = computed(() => {
  const groups = new Map()
  for (const row of currentPageFieldRows.value) {
    const section = row.section || '未分组字段'
    if (!groups.has(section)) groups.set(section, [])
    groups.get(section).push(row)
  }
  return Array.from(groups, ([title, rows]) => ({ title, rows }))
})
const jobProgressItems = computed(() => pageProgressItems(jobStatus.value))

watch(
  [
    response,
    activePage,
    resultTab,
    loading,
    error,
    progress,
    progressStage,
    progressDetail,
    jobStatus,
    activeJobId,
    modelOptions,
    selectedModelId
  ],
  persistRuntimeState,
  { deep: false }
)

function onFileChange(event) {
  setFile(event.target.files?.[0])
}

function onDrop(event) {
  setFile(event.dataTransfer.files?.[0])
}

function setFile(selected) {
  if (!selected) return
  activeRunId += 1
  file.value = selected
  response.value = null
  activePage.value = 1
  resultTab.value = 'fields'
  error.value = ''
  loading.value = false
  jobStatus.value = null
  clearLastJobId()
  persistRuntimeState()
}

function openFilePicker() {
  fileInput.value?.click()
}

async function submitOcr() {
  if (!file.value || loading.value) return
  loading.value = true
  error.value = ''
  response.value = null
  const runId = activeRunId + 1
  activeRunId = runId
  applyJobStatus({
    status: 'uploading',
    progress: 0,
    message: '正在上传并创建识别任务。',
    pages: []
  })

  try {
    const body = new FormData()
    body.append('file', file.value)
    if (selectedModelId.value) body.append('modelId', selectedModelId.value)
    const startResult = await fetch(`${apiBase}/api/ocr/jobs`, { method: 'POST', body })
    if (!startResult.ok) {
      const text = await startResult.text()
      throw new Error(text || `HTTP ${startResult.status}`)
    }
    const startedJob = await startResult.json()
    rememberJobId(startedJob.jobId)
    applyJobStatus(startedJob)
    const completedJob = await pollJobUntilComplete(startedJob.jobId, runId)
    applyCompletedJob(completedJob, runId)
  } catch (exception) {
    if (runId === activeRunId) {
      clearLastJobId()
      error.value = `LLM 结构化识别未完成：${exception.message || '请确认后端已启动并配置 LLM_API_KEY'}`
      applyJobStatus({
        status: 'failed',
        progress: 0,
        message: exception.message || '识别任务失败。',
        error: exception.message || '识别任务失败。',
        pages: jobStatus.value?.pages || []
      })
    }
  } finally {
    if (runId === activeRunId) {
      loading.value = false
    }
  }
}

async function fetchModelOptions() {
  try {
    const result = await fetch(`${apiBase}/api/llm/models`)
    if (!result.ok) throw new Error(`HTTP ${result.status}`)
    const payload = await result.json()
    const models = Array.isArray(payload.models) ? payload.models : []
    modelOptions.value = models.length ? models : fallbackModelOptions()
    ensureSelectedModel(payload.defaultModelId)
  } catch {
    modelOptions.value = fallbackModelOptions()
    ensureSelectedModel()
  }
}

function ensureSelectedModel(defaultModelId = '') {
  const models = modelOptions.value
  if (!models.length) return
  const current = models.find((model) => model.id === selectedModelId.value && model.available !== false)
  if (current) return
  const preferred = models.find((model) => model.id === defaultModelId && model.available !== false)
  const selected = preferred || models.find((model) => model.selected && model.available !== false) || models.find((model) => model.available !== false) || models[0]
  selectedModelId.value = selected.id
}

function fallbackModelOptions() {
  return [
    {
      id: 'local-qwen3.6-35b-a3b',
      label: 'Qwen3.6-35B-A3B 视觉结构化',
      model: 'Qwen3.6-35B-A3B',
      provider: 'OpenAI-compatible local gateway',
      available: true,
      selected: true,
      unavailableReason: ''
    }
  ]
}

async function pollJobUntilComplete(jobId, runId) {
  if (!jobId) throw new Error('后端没有返回识别任务 ID。')
  while (runId === activeRunId) {
    await wait(1000)
    if (runId !== activeRunId) break
    const status = await fetchJobStatus(jobId)
    if (runId !== activeRunId) break
    applyJobStatus(status)
    if (status.status === 'completed') return status
    if (status.status === 'failed') {
      throw new Error(status.error || status.message || '识别任务失败。')
    }
  }
  throw new Error('识别任务已取消。')
}

async function fetchJobStatus(jobId) {
  const result = await fetch(`${apiBase}/api/ocr/jobs/${encodeURIComponent(jobId)}`)
  if (!result.ok) {
    const text = await result.text()
    throw new Error(text || `HTTP ${result.status}`)
  }
  return result.json()
}

async function restoreLastJob() {
  const jobId = activeJobId.value
  if (!jobId || response.value) return
  const runId = activeRunId + 1
  activeRunId = runId
  loading.value = true
  error.value = ''
  applyJobStatus({
    status: 'queued',
    progress: 0,
    message: '正在恢复上一次识别结果。',
    pages: []
  })
  try {
    const status = await fetchJobStatus(jobId)
    if (runId !== activeRunId) return
    applyJobStatus(status)
    if (status.status === 'completed') {
      applyCompletedJob(status, runId)
      return
    }
    if (status.status === 'failed') {
      clearLastJobId()
      return
    }
    const completedJob = await pollJobUntilComplete(jobId, runId)
    applyCompletedJob(completedJob, runId)
  } catch {
    if (runId === activeRunId) {
      clearLastJobId()
    }
  } finally {
    if (runId === activeRunId) {
      loading.value = false
    }
  }
}

function applyCompletedJob(completedJob, runId) {
  if (runId !== activeRunId) return
  if (!completedJob?.result) {
    throw new Error('后端返回完成状态，但没有返回识别结果。')
  }
  response.value = completedJob.result
  activePage.value = response.value?.pages?.[0]?.page || 1
  resultTab.value = 'fields'
  applyJobStatus(completedJob)
  persistRuntimeState()
}

function applyJobStatus(status) {
  jobStatus.value = status
  const state = recognitionProgressState(status)
  progress.value = state.percent
  progressStage.value = state.stage
  progressDetail.value = state.detail
  persistRuntimeState()
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function selectPage(page) {
  activePage.value = page
  persistRuntimeState()
}

function pageSignalCount(page) {
  const documentPage = documentPages.value.find((item) => item.page === page.page)
  return pageStructuredFieldCount(response.value, page.page) || documentPage?.visibleLines.length || 0
}

function confidenceClass(confidence) {
  if (confidence >= 85) return 'confidence-high'
  if (confidence >= 70) return 'confidence-medium'
  return 'confidence-low'
}

function characterTitle(segment) {
  if (!segment?.reviewFlag) return ''
  const reasons = []
  if (segment.status.includes('low_confidence')) reasons.push(`综合置信度 ${segment.confidence}%`)
  if (segment.status.includes('qwen_low_confidence')) reasons.push('Qwen 字符置信度低')
  if (segment.status.includes('no_bbox_evidence')) reasons.push('缺少清晰字符坐标证据')
  if (segment.status.includes('format_invalid')) reasons.push('字段格式校验未通过')
  return reasons.join('；')
}

function reset() {
  activeRunId += 1
  file.value = null
  response.value = null
  activePage.value = 1
  resultTab.value = 'fields'
  error.value = ''
  progress.value = 0
  progressStage.value = ''
  progressDetail.value = ''
  jobStatus.value = null
  loading.value = false
  clearLastJobId()
  if (fileInput.value) fileInput.value.value = ''
  persistRuntimeState()
}

function formatSize(bytes) {
  if (!bytes) return ''
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function rememberJobId(jobId) {
  activeJobId.value = jobId || ''
}

function clearLastJobId() {
  activeJobId.value = ''
}

function getRuntimeState() {
  if (typeof window === 'undefined') return {}
  window.__AIFULLFORMRECONIZATION_STATE__ ||= {}
  return window.__AIFULLFORMRECONIZATION_STATE__
}

function persistRuntimeState() {
  runtimeState.response = response.value
  runtimeState.activePage = activePage.value
  runtimeState.resultTab = resultTab.value
  runtimeState.loading = loading.value
  runtimeState.error = error.value
  runtimeState.progress = progress.value
  runtimeState.progressStage = progressStage.value
  runtimeState.progressDetail = progressDetail.value
  runtimeState.jobStatus = jobStatus.value
  runtimeState.activeJobId = activeJobId.value
  runtimeState.modelOptions = modelOptions.value
  runtimeState.selectedModelId = selectedModelId.value
}

onMounted(() => {
  fetchModelOptions()
  restoreLastJob()
})

onBeforeUnmount(() => {
  activeRunId += 1
})
</script>

<template>
  <main class="ocr-app">
    <header class="app-header">
      <div class="brand-block">
        <p class="eyebrow">Full-page LLM Demo</p>
        <h1>申请材料整页结构化识别演示</h1>
        <p class="header-copy">PDF 或图片按整页送入多模态大模型，右侧展示自动生成的结构化 JSON。</p>
      </div>
      <div class="model-pill">
        <label for="llm-model-select">识别模型</label>
        <select id="llm-model-select" v-model="selectedModelId" :disabled="loading">
          <option
            v-for="model in modelOptions"
            :key="model.id"
            :value="model.id"
            :disabled="model.available === false"
          >
            {{ model.label }}{{ model.available === false ? `（${model.unavailableReason || '未配置'}）` : '' }}
          </option>
        </select>
      </div>
    </header>

    <section v-if="!response" class="upload-stage">
      <div class="upload-panel">
        <div class="upload-heading">
          <h2>上传源文件</h2>
          <p>支持 PDF、PNG、JPG。系统保留每页快照，不使用预设坐标框或裁剪区域。</p>
        </div>

        <button class="dropzone" type="button" @click="openFilePicker" @drop.prevent="onDrop" @dragover.prevent>
          <input
            ref="fileInput"
            type="file"
            accept="application/pdf,image/png,image/jpeg,image/jpg,image/webp"
            @change="onFileChange"
          />
          <span class="upload-glyph" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M12 16V4" />
              <path d="m7 9 5-5 5 5" />
              <path d="M5 20h14" />
            </svg>
          </span>
          <strong>{{ file?.name || '选择或拖入申请材料' }}</strong>
          <small v-if="file">{{ formatSize(file.size) }}</small>
          <small v-else>PDF / PNG / JPG</small>
        </button>

        <div class="capability-row" aria-label="识别能力">
          <span>整页识别</span>
          <span>中文</span>
          <span>English</span>
          <span>手写内容</span>
        </div>
        <p v-if="selectedModelUnavailableReason" class="hint-text">{{ selectedModelUnavailableReason }}</p>

        <div v-if="loading" class="progress-box" aria-live="polite">
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: `${progress}%` }" />
          </div>
          <div class="progress-meta">
            <span>{{ progressStage }}</span>
            <strong>{{ progress.toFixed(0) }}%</strong>
          </div>
          <p class="progress-detail">{{ progressDetail }}</p>
          <div v-if="jobProgressItems.length" class="job-page-progress" aria-label="每页识别进度">
            <span
              v-for="item in jobProgressItems"
              :key="item.page"
              class="job-page-pill"
              :class="`job-page-${item.status}`"
              :title="item.message"
            >
              <strong>Page {{ item.page }}</strong>
              <small>{{ item.label }}<span v-if="item.diagnostic"> · {{ item.diagnostic }}</span></small>
            </span>
          </div>
        </div>

        <button v-else class="primary-action" type="button" :disabled="!file || !!selectedModelUnavailableReason" @click="submitOcr">
          开始 LLM 识别
        </button>
        <p v-if="error" class="error-text">{{ error }}</p>
      </div>
    </section>

    <section v-else class="result-stage">
      <div class="result-toolbar">
        <div class="file-summary">
          <span class="file-label">源文件</span>
          <strong>{{ response.filename }}</strong>
          <span>{{ response.pageCount }} 页</span>
        </div>
        <div class="toolbar-actions">
          <span class="status-chip">{{ response.engineStatus?.extractionMode || response.model }}</span>
          <button class="secondary-action" type="button" @click="reset">重新上传</button>
        </div>
      </div>

      <nav class="page-strip" aria-label="分页">
        <button
          v-for="page in pages"
          :key="page.page"
          type="button"
          :class="{ active: page.page === activePage }"
          @click="selectPage(page.page)"
        >
          <span>Page {{ page.page }}</span>
          <strong>{{ pageSignalCount(page) }}</strong>
        </button>
        <div class="document-conclusion" aria-live="polite">{{ globalFieldConclusion }}</div>
      </nav>

      <div class="split-workspace">
        <section class="source-pane" aria-label="源文件快照">
          <div class="pane-header">
            <div>
              <h2>源文件快照</h2>
              <p>第 {{ currentPageIndex }} / {{ pages.length }} 页</p>
            </div>
          </div>
          <div class="document-canvas">
            <div v-if="currentPage?.sourceImageDataUrl" class="document-image-frame">
              <img :src="currentPage.sourceImageDataUrl" alt="源文件页面快照" />
            </div>
            <div v-else class="empty-panel">该页没有可显示的快照</div>
          </div>
        </section>

        <section class="ocr-pane" aria-label="结构化识别结果">
          <div class="pane-header ocr-header">
            <div>
              <h2>
                {{
                  resultTab === 'json'
                    ? '接口响应 JSON'
                    : resultTab === 'structured'
                      ? '结构化识别结果'
                      : '字段提取结果'
                }}
              </h2>
              <p v-if="resultTab === 'fields'">
                当前页约 {{ currentPage ? pageSignalCount(currentPage) : 0 }} 个字段；字段名称和值由模型按整页自动判断。
              </p>
              <p v-else-if="resultTab === 'structured'">
                右侧展示模型按页面生成的完整结构化 JSON。
              </p>
              <p v-else>已隐藏每页快照的长 Base64 内容，便于调试接口响应。</p>
            </div>
            <div class="segmented-control" role="tablist" aria-label="结果视图">
              <button type="button" :class="{ active: resultTab === 'fields' }" @click="resultTab = 'fields'">字段提取</button>
              <button type="button" :class="{ active: resultTab === 'structured' }" @click="resultTab = 'structured'">结构化</button>
              <button type="button" :class="{ active: resultTab === 'json' }" @click="resultTab = 'json'">JSON</button>
            </div>
          </div>

          <div v-if="resultTab === 'fields'" class="fields-document">
            <div class="page-conclusion">{{ currentPageConclusion }}</div>
            <div v-if="currentPageFieldRows.length" class="field-sections">
              <section v-for="section in currentPageFieldSections" :key="section.title" class="field-section">
                <h3>{{ section.title }}</h3>
                <div class="field-table">
                  <article
                    v-for="row in section.rows"
                    :key="row.id"
                    class="field-row"
                    :class="{ 'field-row-empty': row.rawValue === null || row.rawValue === undefined || row.rawValue === '' }"
                  >
                    <div class="field-label">
                      <strong>{{ row.fieldName }}</strong>
                      <span>{{ row.path }}</span>
                    </div>
                    <div class="field-crop">
                      <img v-if="row.snapshotDataUrl" :src="row.snapshotDataUrl" alt="字段识别区域快照" />
                      <span v-else>{{ cropPlaceholderText(row) }}</span>
                    </div>
                    <div class="field-value">
                      <strong class="field-value-text">
                        <span
                          v-for="segment in row.charSegments"
                          :key="`${row.id}:${segment.index}`"
                          :class="{ 'char-review': segment.reviewFlag }"
                          :title="characterTitle(segment)"
                        >{{ segment.text }}</span>
                      </strong>
                    </div>
                    <div class="field-confidence" :class="confidenceClass(row.confidence)">
                      {{ row.confidence }}%
                    </div>
                  </article>
                </div>
              </section>
            </div>
            <div v-else class="empty-panel">本页暂未识别到字段</div>
          </div>

          <pre v-else-if="resultTab === 'structured'" class="json-panel structured-json">{{ structuredPreview }}</pre>

          <pre v-else class="json-panel">{{ jsonPreview }}</pre>
        </section>
      </div>
    </section>
  </main>
</template>
