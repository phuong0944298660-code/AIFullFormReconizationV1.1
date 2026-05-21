export function recognitionProgressState(jobStatus) {
  const status = jobStatus || {}
  const pages = Array.isArray(status.pages) ? status.pages : []
  const active = currentJobPage(status)
  return {
    percent: clampPercent(status.progress ?? progressFromPages(pages, status.status)),
    stage: stageText(status, active),
    detail: detailText(status, active),
    pages: pageProgressItems(status)
  }
}

export function pageProgressItems(jobStatus) {
  const pages = Array.isArray(jobStatus?.pages) ? jobStatus.pages : []
  return pages.map((page) => ({
    page: page.page,
    status: page.status || 'pending',
    percent: clampPercent(page.percent ?? 0),
    label: pageLabel(page.status),
    message: page.message || '',
    elapsedMillis: safeNumber(page.elapsedMillis),
    attempt: safeNumber(page.attempt),
    attemptReason: page.attemptReason || '',
    lastAttemptMillis: safeNumber(page.lastAttemptMillis),
    currentAttemptMillis: safeNumber(page.currentAttemptMillis),
    diagnostic: pageDiagnostic(page)
  }))
}

export function currentJobPage(jobStatus) {
  const pages = Array.isArray(jobStatus?.pages) ? jobStatus.pages : []
  return pages.find((page) => page.status === 'running')
    || pages.find((page) => page.status === 'pending')
    || null
}

function stageText(status, active) {
  if (status?.status === 'completed') return '识别完成'
  if (status?.status === 'failed') return '识别失败'
  if (status?.status === 'rendering') return '渲染页面快照'
  if (active?.status === 'running') return `Page ${active.page} 识别中`
  if (status?.pageCount > 0) return '等待页面识别'
  return '创建识别任务'
}

function detailText(status, active) {
  if (status?.status === 'completed') return '全部页面已完成，正在展示识别结果。'
  if (status?.status === 'failed') return status.error || status.message || '识别任务失败。'
  if (active?.status === 'running') {
    const diagnostic = activeDetailDiagnostic(active)
    return `${active.message || `正在识别第 ${active.page} 页。`}${diagnostic} 已完成 ${status.completedPages || 0} / ${status.pageCount || 0} 页。`
  }
  if (status?.status === 'rendering') return status.message || '正在渲染 PDF 为每页快照。'
  if (status?.pageCount > 0) return `已生成 ${status.pageCount} 页快照，等待 Qwen 返回。`
  return status?.message || '正在上传并创建识别任务。'
}

function activeDetailDiagnostic(page) {
  const attempt = safeNumber(page.attempt)
  const elapsedMillis = safeNumber(page.elapsedMillis)
  const currentAttemptMillis = safeNumber(page.currentAttemptMillis)
  const parts = []
  if (attempt > 0) {
    parts.push(`第 ${attempt} 次请求${attemptReasonText(page.attemptReason)}`)
  }
  if (elapsedMillis > 0) {
    parts.push(`总耗时 ${formatDuration(elapsedMillis)}`)
  }
  if (currentAttemptMillis > 0) {
    parts.push(`本次 ${formatDuration(currentAttemptMillis)}`)
  }
  return parts.length ? ` ${parts.join('，')}。` : ''
}

function pageDiagnostic(page) {
  const attempt = safeNumber(page.attempt)
  const elapsedMillis = safeNumber(page.elapsedMillis)
  if (attempt <= 0 && elapsedMillis <= 0) return ''
  const parts = []
  if (attempt > 0) parts.push(`${attempt}次`)
  if (elapsedMillis > 0) parts.push(formatDuration(elapsedMillis))
  return parts.join(' / ')
}

function attemptReasonText(reason) {
  if (reason === 'empty_retry') return '（空结果重试）'
  if (reason === 'initial') return '（首次请求）'
  return reason ? `（${reason}）` : ''
}

function progressFromPages(pages, status) {
  if (status === 'completed') return 100
  if (!pages.length) return 0
  const done = pages.filter((page) => page.status === 'completed' || page.status === 'failed').length
  return Math.round((done / pages.length) * 100)
}

function pageLabel(status) {
  if (status === 'completed') return '已完成'
  if (status === 'running') return '识别中'
  if (status === 'failed') return '失败'
  return '等待'
}

function clampPercent(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  return Math.max(0, Math.min(100, Math.round(number)))
}

function safeNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

function formatDuration(ms) {
  const value = Number(ms)
  if (!Number.isFinite(value) || value <= 0) return '0秒'
  const seconds = Math.max(1, Math.floor(value / 1000))
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  if (minutes > 0) return `${minutes}分${remainder}秒`
  return `${seconds}秒`
}
