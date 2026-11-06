<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import {
  ChatDotRound,
  CircleCheck,
  Connection,
  DataAnalysis,
  Delete,
  Promotion,
  Refresh,
  Search
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getAgentStatus,
  getSkillDetail,
  getPendingApprovals,
  getSessionDetail,
  getSessionHistory,
  streamApproval,
  streamChat
} from './services/api'

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: true
})

const DEFAULT_TENANT = 'alice'
const TENANT_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
const SESSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/
const RESERVED_TENANTS = new Set([
  'agents',
  'knowledge',
  'memory',
  'plans',
  'skills',
  'subagents',
  'tasks'
])
const WINDOWS_DEVICE_NAME = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$/i
const tenantOptions = ['alice', 'bob', 'research-team']

const DEFAULT_RECOMMENDATIONS = [
  {
    id: 'harness-architecture',
    title: '理解 Harness 的长期运行机制',
    prompt: '深度研究：AgentScope Java 2.0 的 Harness 架构如何支持长期运行智能体？',
    reason: '适合第一次观察会话、记忆与技能沉淀'
  },
  {
    id: 'rag-comparison',
    title: '比较两种检索方案',
    prompt: '调研并比较 RAG 与 Agentic Search 的适用场景、成本和主要风险。',
    reason: '适合触发多轮模拟检索与证据综合'
  },
  {
    id: 'memory-check',
    title: '验证长期记忆',
    prompt: '请基于当前租户的长期记忆，整理已知项目约束、待验证假设和下一步行动。',
    reason: '适合验证跨会话的持久化恢复'
  }
]

function createWelcomeMessage(userId) {
  return {
    id: `welcome-${userId}`,
    role: 'assistant',
    content: `当前工作区：**${userId}**。会话、长期记忆和技能文件均按租户隔离保存。`,
    createdAt: new Date()
  }
}

function createEmptyStatus(userId) {
  return {
    lifecycle: 'CONNECTING',
    agentName: 'deep-research-agent',
    model: '—',
    userId,
    workspace: './workspace',
    tenantWorkspace: `./workspace/${userId}`,
    evolution: {
      activeSkillCount: 0,
      draftSkillCount: 0,
      archivedSkillCount: 0,
      skills: []
    },
    memory: {
      summary: '正在读取当前租户的长期记忆…',
      dailyFileCount: 0,
      dailyFiles: [],
      conversationCount: 0,
      sessionLogCount: 0
    },
    search: {
      totalSearches: 0,
      lastQuery: null,
      lastSearchedAt: null
    },
    recommendations: DEFAULT_RECOMMENDATIONS
  }
}

const tenantId = ref(loadTenantId())
const sessionId = ref(loadSessionId(tenantId.value))
const messages = ref([createWelcomeMessage(tenantId.value)])
const draft = ref('')
const sending = ref(false)
const statusLoading = ref(false)
const status = ref(createEmptyStatus(tenantId.value))
const chatScroller = ref(null)
const evolutionFlash = ref(false)
const hasLoadedStatus = ref(false)
const streamingHint = ref('正在连接 HarnessAgent…')
const historyDrawerVisible = ref(false)
const historyLoading = ref(false)
const historyRestoring = ref(false)
const sessionHistory = ref([])
const pendingApproval = ref(null)
const approvalRecovering = ref(true)
const skillDrawerVisible = ref(false)
const skillDetail = ref(null)
const skillLoading = ref(false)
let skillRequestSequence = 0
const approvalSubmitting = ref(false)
let pollTimer
let flashTimer
let statusRequestSequence = 0

const skills = computed(() => status.value.evolution?.skills || [])
const memory = computed(() => status.value.memory || createEmptyStatus(tenantId.value).memory)
const searchState = computed(() => status.value.search || createEmptyStatus(tenantId.value).search)
const recommendations = computed(() => {
  const dynamicRecommendations = status.value.recommendations || []
  return dynamicRecommendations.length ? dynamicRecommendations : DEFAULT_RECOMMENDATIONS
})
const isOnline = computed(() => status.value.lifecycle === 'READY')
const shortSessionId = computed(() => sessionId.value.slice(0, 8))
const isWelcomeOnly = computed(
  () => messages.value.length === 1 && messages.value[0].id === `welcome-${tenantId.value}`
)

function createSessionId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID()
  }
  return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function loadTenantId() {
  const stored = normalizeTenantId(localStorage.getItem('deep-research-tenant-id'))
  return isValidTenantId(stored) ? stored : DEFAULT_TENANT
}

function sessionStorageKey(userId) {
  return `deep-research-session-id:${userId}`
}

function loadSessionId(userId) {
  const key = sessionStorageKey(userId)
  const stored = localStorage.getItem(key)
  if (
    stored &&
    SESSION_PATTERN.test(stored) &&
    !stored.endsWith('.') &&
    !WINDOWS_DEVICE_NAME.test(stored)
  ) {
    return stored
  }
  const created = createSessionId()
  localStorage.setItem(key, created)
  return created
}

function isValidTenantId(value) {
  return (
    typeof value === 'string' &&
    TENANT_PATTERN.test(value) &&
    !value.endsWith('.') &&
    !RESERVED_TENANTS.has(value.toLowerCase()) &&
    !WINDOWS_DEVICE_NAME.test(value)
  )
}

function normalizeTenantId(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

function renderMarkdown(content) {
  return DOMPurify.sanitize(markdown.render(content || ''))
}

function chooseSuggestion(recommendation) {
  if (sending.value || pendingApproval.value) return
  draft.value = typeof recommendation === 'string' ? recommendation : recommendation.prompt
  nextTick(() => document.querySelector('.composer-textarea textarea')?.focus())
}

function createTraceStep(kind, title, detail = '') {
  return {
    id: `trace-${createSessionId()}`,
    kind,
    title,
    detail,
    createdAt: new Date()
  }
}

function appendTrace(message, kind, title, detail = '') {
  if (!message) return
  if (!Array.isArray(message.timeline)) message.timeline = []
  // 同一阶段只保留一次，但每次搜索/Skill 调用都保留，方便看出多轮研究确实发生了。
  const singletonKinds = new Set(['started', 'analysis', 'complete', 'memory'])
  const isDuplicate =
    singletonKinds.has(kind) &&
    message.timeline.some(
      (step) => step.kind === kind && step.title === title && step.detail === detail
    )
  if (!isDuplicate) message.timeline.push(createTraceStep(kind, title, detail))
}

function describeToolTrace(toolName) {
  const normalized = (toolName || '').toLowerCase()
  if (normalized.startsWith('plan_')) {
    return { kind: 'approval', title: '处理研究计划', detail: toolName }
  }
  if (normalized === 'agent_spawn' || normalized === 'wait_async_results') {
    return { kind: 'subagent', title: '协作研究', detail: toolName }
  }
  if (normalized.includes('tavily')) {
    return {
      kind: 'search',
      title: '调用 Tavily Mock 检索',
      detail: '离线模拟检索结果将作为研究证据输入模型'
    }
  }
  if (normalized.includes('propose_skill')) {
    return {
      kind: 'evolution',
      title: '正在准备候选技能',
      detail: '完成后会经过安全检查，再按配置写入技能库'
    }
  }
  if (normalized.includes('load_skill') || normalized.includes('read_skill')) {
    return {
      kind: 'skill',
      title: '复用已有 Skill 方法',
      detail: '正在加载当前租户可见的可复用研究步骤'
    }
  }
  if (normalized.includes('skill')) {
    return {
      kind: 'skill',
      title: '正在使用可复用技能',
      detail: `工具：${toolName}`
    }
  }
  if (normalized.includes('memory')) {
    return {
      kind: 'memory',
      title: '正在处理记忆信息',
      detail: `工具：${toolName}`
    }
  }
  return {
    kind: 'tool',
    title: '正在调用研究工具',
    detail: `工具：${toolName || '未知工具'}`
  }
}

function bindPersistentSession(event) {
  if (!event.sessionId) return
  const eventTenant = normalizeTenantId(event.userId || tenantId.value)
  if (!isValidTenantId(eventTenant) || eventTenant !== tenantId.value) return
  sessionId.value = event.sessionId
  localStorage.setItem(sessionStorageKey(eventTenant), event.sessionId)
}

function describeApprovalTools(tools) {
  const names = (tools || []).map((tool) => tool.name).filter(Boolean)
  return names.length ? `待确认工具：${names.join('、')}` : '待确认的工具操作'
}

function formatApprovalInput(input) {
  try {
    return JSON.stringify(input || {}, null, 2)
  } catch {
    return '工具参数暂时不可展示'
  }
}

function clearPendingApprovalFor(messageId) {
  if (pendingApproval.value?.messageId === messageId) {
    pendingApproval.value = null
  }
}

function applyAgentEvent(event, streamingMessageId) {
  const streamingMessage = messages.value.find((item) => item.id === streamingMessageId)

  if (event.type === 'model_usage' && streamingMessage && event.modelUsage) {
    const totals = streamingMessage.modelUsage || { inputTokens: 0, outputTokens: 0, cachedTokens: 0, reasoningTokens: 0 }
    for (const key of Object.keys(totals)) totals[key] += event.modelUsage[key] || 0
    streamingMessage.modelUsage = totals
    return
  }
  if (event.type === 'subagent') {
    appendTrace(streamingMessage, 'subagent', event.message, event.toolName)
    return
  }

  if (event.type === 'tool_detail' && streamingMessage) {
    const detail = event.toolExecution
    if (!detail) return
    if (!streamingMessage.tools) streamingMessage.tools = []
    const index = streamingMessage.tools.findIndex(tool => tool.id === detail.id)
    if (index < 0) streamingMessage.tools.push(detail)
    else streamingMessage.tools[index] = detail
    void scrollToBottom()
    return
  }

  if (event.type === 'started') {
    bindPersistentSession(event)
    appendTrace(
      streamingMessage,
      'started',
      '已绑定持久化会话',
      event.sessionId ? `会话 ${event.sessionId.slice(0, 8)}` : event.message || ''
    )
    return
  }

  if (event.type === 'delta' && streamingMessage) {
    streamingMessage.waitingApproval = false
    streamingMessage.streaming = true
    streamingMessage.content += event.delta || ''
    appendTrace(streamingMessage, 'analysis', '正在综合检索证据', '模型正在把研究过程组织为最终报告')
    streamingHint.value = '正在生成研究报告…'
    void scrollToBottom()
    return
  }

  if (event.type === 'tool') {
    const trace = describeToolTrace(event.toolName)
    appendTrace(streamingMessage, trace.kind, trace.title, trace.detail)
    streamingHint.value = event.message || `正在调用工具：${event.toolName || '未知工具'}`
    void scrollToBottom()
    return
  }

  if (event.type === 'ask') {
    bindPersistentSession(event)
    const tools = event.approvalTools || []
    const plan = tools.some((tool) => tool.name === 'plan_exit')
    if (!event.approvalId) {
      throw new Error('研究流程请求人工确认，但没有返回审批编号')
    }
    pendingApproval.value = {
      approvalId: event.approvalId,
      userId: event.userId || tenantId.value,
      sessionId: event.sessionId || sessionId.value,
      tools,
      plan,
      messageId: streamingMessageId
    }
    if (streamingMessage) {
      streamingMessage.streaming = false
      streamingMessage.waitingApproval = true
      appendTrace(
        streamingMessage,
        'approval',
        plan ? '等待确认研究计划' : '等待确认工具操作',
        describeApprovalTools(tools)
      )
    }
    streamingHint.value = plan ? '等待你确认研究计划…' : '等待你确认工具操作…'
    void scrollToBottom()
    return
  }

  if (event.type === 'complete') {
    bindPersistentSession(event)
    clearPendingApprovalFor(streamingMessageId)
    if (streamingMessage) {
      streamingMessage.waitingApproval = false
      streamingMessage.content = event.answer || streamingMessage.content || '智能体未返回可展示文本。'
      streamingMessage.createdAt = event.completedAt ? new Date(event.completedAt) : new Date()
      streamingMessage.streaming = false
      appendTrace(
        streamingMessage,
        'memory',
        event.memoryUpdated ? '已检测到长期记忆更新' : '长期记忆由后台处理',
        '研究资产面板按实际写入结果刷新'
      )
      if (event.evolved) {
        appendTrace(streamingMessage, 'evolution', '新 Skill 已自动提升', (event.newSkills || []).join('、'))
      }
      appendTrace(streamingMessage, 'complete', '研究报告已完成', '最终回答和会话回放文本已持久化')
    }

    if (event.evolved) {
      messages.value.push({
        id: createSessionId(),
        role: 'evolution',
        content: `已沉淀新技能：${(event.newSkills || []).map((name) => `\`${name}\``).join('、')}`,
        createdAt: new Date()
      })
      triggerEvolutionFlash()
    }
    void scrollToBottom()
  }
}

function handleStreamFailure(error, streamingMessageId) {
  const streamIndex = messages.value.findIndex((item) => item.id === streamingMessageId)
  const streamMessage = streamIndex >= 0 ? messages.value[streamIndex] : null
  for (const tool of streamMessage?.tools || []) {
    if (tool.state === 'RUNNING') tool.state = 'UNKNOWN'
  }
  clearPendingApprovalFor(streamingMessageId)
  if (streamMessage?.content || streamMessage?.waitingApproval) {
    streamMessage.streaming = false
    streamMessage.waitingApproval = false
    appendTrace(streamMessage, 'error', '研究调用未完成', error.message)
  } else if (streamIndex >= 0) {
    messages.value.splice(streamIndex, 1)
  }
  messages.value.push({
    id: createSessionId(),
    role: 'error',
    content: `调用失败：${error.message}`,
    createdAt: new Date()
  })
  ElMessage.error(error.message)
}

async function submitMessage() {
  const message = draft.value.trim()
  if (!message || sending.value || pendingApproval.value || approvalRecovering.value) return

  const streamingMessageId = createSessionId()
  messages.value.push({
    id: createSessionId(),
    role: 'user',
    content: message,
    createdAt: new Date()
  })
  messages.value.push({
    id: streamingMessageId,
    role: 'assistant',
    content: '',
    createdAt: new Date(),
    streaming: true,
    timeline: [
      createTraceStep('started', '已建立研究上下文', `租户 ${tenantId.value} · 会话 ${shortSessionId.value}`)
    ]
  })
  draft.value = ''
  sending.value = true
  streamingHint.value = '正在建立研究会话…'
  await scrollToBottom()

  try {
    await streamChat(message, sessionId.value, tenantId.value, (event) =>
      applyAgentEvent(event, streamingMessageId)
    )
  } catch (error) {
    handleStreamFailure(error, streamingMessageId)
  } finally {
    sending.value = false
    streamingHint.value = ''
    await refreshStatus()
    await loadSessionHistory({ silent: true })
    await scrollToBottom()
  }
}

async function submitApproval(decision) {
  const approval = pendingApproval.value
  if (!approval || sending.value || approvalSubmitting.value) return

  const streamingMessage = messages.value.find((item) => item.id === approval.messageId)
  approvalSubmitting.value = true
  sending.value = true
  pendingApproval.value = null
  if (streamingMessage) {
    streamingMessage.waitingApproval = false
    streamingMessage.streaming = true
    appendTrace(
      streamingMessage,
      'approval',
      decision === 'allow' ? '用户已批准本次请求' : '用户已拒绝本次请求',
      describeApprovalTools(approval.tools)
    )
  }
  streamingHint.value = decision === 'allow' ? '正在继续已批准的研究…' : '正在处理拒绝结果…'
  await scrollToBottom()

  try {
    await streamApproval(
      approval.approvalId,
      approval.sessionId,
      approval.userId,
      decision,
      (event) => applyAgentEvent(event, approval.messageId)
    )
  } catch (error) {
    handleStreamFailure(error, approval.messageId)
    await recoverApproval(approval.messageId)
  } finally {
    approvalSubmitting.value = false
    sending.value = false
    streamingHint.value = ''
    await refreshStatus()
    await loadSessionHistory({ silent: true })
    await scrollToBottom()
  }
}

async function refreshStatus(options = {}) {
  const requestedTenant = tenantId.value
  const requestSequence = ++statusRequestSequence
  if (!options.silent) statusLoading.value = true
  try {
    const previousCount = status.value.evolution?.activeSkillCount || 0
    const nextStatus = await getAgentStatus(requestedTenant)
    if (requestedTenant !== tenantId.value || requestSequence !== statusRequestSequence) return
    status.value = nextStatus
    if (hasLoadedStatus.value && nextStatus.evolution.activeSkillCount > previousCount) {
      triggerEvolutionFlash()
    }
    hasLoadedStatus.value = true
  } catch (error) {
    if (requestedTenant !== tenantId.value || requestSequence !== statusRequestSequence) return
    status.value = { ...status.value, lifecycle: 'OFFLINE' }
    if (!options.silent) ElMessage.warning(`状态拉取失败：${error.message}`)
  } finally {
    if (requestedTenant === tenantId.value && requestSequence === statusRequestSequence) {
      statusLoading.value = false
    }
  }
}

function triggerEvolutionFlash() {
  evolutionFlash.value = true
  window.clearTimeout(flashTimer)
  flashTimer = window.setTimeout(() => {
    evolutionFlash.value = false
  }, 5000)
}

function resetConversation() {
  if (sending.value || pendingApproval.value) return
  sessionId.value = createSessionId()
  localStorage.setItem(sessionStorageKey(tenantId.value), sessionId.value)
  messages.value = [createWelcomeMessage(tenantId.value)]
  ElMessage.success(`已为租户 ${tenantId.value} 创建新会话`)
}

async function openHistory() {
  if (sending.value || pendingApproval.value) return
  historyDrawerVisible.value = true
  await loadSessionHistory()
}

async function loadSessionHistory(options = {}) {
  const requestedTenant = tenantId.value
  historyLoading.value = true
  try {
    const payload = await getSessionHistory(requestedTenant)
    if (requestedTenant !== tenantId.value) return
    sessionHistory.value = payload.sessions || []
  } catch (error) {
    if (!options.silent) ElMessage.warning(`会话历史读取失败：${error.message}`)
  } finally {
    if (requestedTenant === tenantId.value) historyLoading.value = false
  }
}

async function restoreSession(summary) {
  if (sending.value || pendingApproval.value || historyRestoring.value) return

  const requestedTenant = tenantId.value
  historyRestoring.value = true
  try {
    const detail = await getSessionDetail(requestedTenant, summary.sessionId)
    if (requestedTenant !== tenantId.value) return

    sessionId.value = detail.sessionId
    localStorage.setItem(sessionStorageKey(requestedTenant), detail.sessionId)
    if ((detail.messages || []).length) {
      messages.value = detail.messages.map((message) => ({
        id: createSessionId(),
        role: message.role === 'user' ? 'user' : 'assistant',
        content: message.content,
        createdAt: new Date(message.createdAt),
        timeline: []
      }))
    } else {
      messages.value = [
        {
          id: `restored-${detail.sessionId}`,
          role: 'assistant',
          content: `已恢复会话 \`${detail.sessionId}\`。此会话创建于“历史回放”功能启用之前，因此旧消息文本不可展示；但 Harness 的持久化上下文会在下一条提问时继续生效。`,
          createdAt: new Date(detail.updatedAt || Date.now()),
          timeline: []
        }
      ]
    }
    historyDrawerVisible.value = false
    await recoverApproval()
    await refreshStatus({ silent: true })
    await scrollToBottom()
    ElMessage.success(`已恢复会话：${detail.title}`)
  } catch (error) {
    ElMessage.error(`恢复会话失败：${error.message}`)
  } finally {
    historyRestoring.value = false
  }
}

async function handleTenantChange(nextTenant) {
  if (sending.value || pendingApproval.value) {
    ElMessage.warning('请先处理当前的确认请求')
    return
  }
  const normalized = normalizeTenantId(nextTenant)
  if (!isValidTenantId(normalized)) {
    ElMessage.error('租户 ID 格式不合法或使用了 Workspace 保留名称')
    tenantId.value = loadTenantId()
    return
  }

  tenantId.value = normalized
  localStorage.setItem('deep-research-tenant-id', normalized)
  sessionId.value = loadSessionId(normalized)
  messages.value = [createWelcomeMessage(normalized)]
  status.value = createEmptyStatus(normalized)
  hasLoadedStatus.value = false
  evolutionFlash.value = false
  historyDrawerVisible.value = false
  historyLoading.value = false
  sessionHistory.value = []
  skillDrawerVisible.value = false
  skillDetail.value = null
  await recoverApproval()
  await refreshStatus()
  ElMessage.success(`已切换到租户 ${normalized}`)
}

async function scrollToBottom() {
  await nextTick()
  if (chatScroller.value) {
    chatScroller.value.scrollTop = chatScroller.value.scrollHeight
  }
}

function formatClock(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function formatDateTime(value) {
  if (!value) return '尚未产生'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '尚未产生'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function skillTagType(state) {
  if (state === 'ACTIVE') return 'success'
  if (state === 'DRAFT') return 'warning'
  return 'info'
}

function skillScopeLabel(scope) {
  return scope === 'TENANT' ? tenantId.value : '共享'
}

async function openSkill(skill) {
  const requestedTenant = tenantId.value
  const sequence = ++skillRequestSequence
  skillDrawerVisible.value = true
  skillLoading.value = true
  skillDetail.value = null
  try {
    const result = await getSkillDetail(requestedTenant, skill.relativePath)
    if (requestedTenant === tenantId.value && sequence === skillRequestSequence) skillDetail.value = result
  } catch (error) {
    if (requestedTenant === tenantId.value && sequence === skillRequestSequence) ElMessage.error(error.message)
  } finally {
    if (sequence === skillRequestSequence) skillLoading.value = false
  }
}

// 只恢复服务端仍有效的审批；刷新不会自动批准，也不重新发起模型调用。
async function recoverApproval(existingMessageId) {
  approvalRecovering.value = true
  const userId = tenantId.value
  const currentSession = sessionId.value
  try {
    const approvals = await getPendingApprovals(userId, currentSession)
    if (userId !== tenantId.value || currentSession !== sessionId.value) return
    if (!approvals.length) return
    let message = messages.value.find((item) => item.id === existingMessageId)
    if (!message) {
      message = { id: createSessionId(), role: 'assistant', content: '',
        createdAt: new Date(), timeline: [], tools: [] }
      messages.value.push(message)
    }
    applyAgentEvent(approvals[0], message.id)
  } catch (error) {
    ElMessage.warning(`待审批操作恢复失败：${error.message}`)
  } finally {
    if (userId === tenantId.value && currentSession === sessionId.value) approvalRecovering.value = false
  }
}

function toolStateLabel(state) {
  return { SUCCESS: '成功', ERROR: '失败', DENIED: '已拒绝',
    INTERRUPTED: '已中断', RUNNING: '执行中' }[state] || '状态未知'
}

function skillLoadEvidence(tool) {
  // 仅标记实际成功的读取调用，不根据模型自述或技能文件存在推断复用。
  if (tool.state !== 'SUCCESS') return false
  return tool.name === 'load_skill_through_path' && /SKILL\.md/i.test(tool.input || '')
}

onMounted(async () => {
  await refreshStatus()
  await recoverApproval()
  pollTimer = window.setInterval(() => refreshStatus({ silent: true }), 4000)
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  window.clearTimeout(flashTimer)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand-block">
        <div class="brand-orb">
          <DataAnalysis />
        </div>
        <div>
          <div class="eyebrow">AgentScope Java 2.0 · Harness</div>
          <h1>深度研究工作台</h1>
        </div>
      </div>

      <div class="topbar-meta">
        <div class="tenant-switcher">
          <span>租户</span>
          <el-select
            v-model="tenantId"
            size="small"
            filterable
            allow-create
            default-first-option
            :disabled="sending || pendingApproval"
            aria-label="切换租户"
            @change="handleTenantChange"
          >
            <el-option
              v-for="tenant in tenantOptions"
              :key="tenant"
              :label="tenant"
              :value="tenant"
            />
          </el-select>
        </div>
        <div class="model-chip">
          <span>模型</span>
          {{ status.model }}
        </div>
        <div class="online-state" :class="{ offline: !isOnline }">
          <i></i>
          {{ isOnline ? '服务正常' : '服务不可用' }}
        </div>
      </div>
    </header>

    <main class="workspace-grid">
      <section class="chat-panel surface-card">
        <div class="panel-heading chat-heading">
          <div>
            <span class="section-kicker">当前会话</span>
            <h2><ChatDotRound /> 研究对话</h2>
          </div>
          <div class="session-actions">
            <span class="tenant-badge">租户 · {{ tenantId }}</span>
            <span class="session-id">会话 · {{ shortSessionId }}</span>
            <el-button plain size="small" :disabled="sending || pendingApproval" @click="openHistory">历史</el-button>
            <el-tooltip content="创建新会话" placement="bottom">
              <el-button circle plain :icon="Delete" :disabled="sending || pendingApproval" @click="resetConversation" />
            </el-tooltip>
          </div>
        </div>

        <div ref="chatScroller" class="chat-scroller">
          <div v-if="isWelcomeOnly" class="starter-area">
            <div class="starter-symbol"><Search /></div>
            <h3>从一个明确的问题开始</h3>
            <p>
              当前租户为 {{ tenantId }}。复杂研究会保存检索记录、研究结论与可复用技能；右侧会同步展示工作区状态。
            </p>
            <div class="suggestion-list">
              <button
                v-for="recommendation in recommendations"
                :key="recommendation.id"
                type="button"
                :title="recommendation.prompt"
                :disabled="sending || pendingApproval"
                @click="chooseSuggestion(recommendation)"
              >
                <Search />
                <span class="suggestion-copy">
                  <strong>{{ recommendation.title }}</strong>
                  <small>{{ recommendation.reason }}</small>
                </span>
              </button>
            </div>
          </div>

          <article
            v-for="message in messages"
            :key="message.id"
            class="message-row"
            :class="`message-${message.role}`"
          >
            <div v-if="message.role !== 'user'" class="message-avatar">
              <DataAnalysis />
            </div>
            <div class="message-wrap">
              <div class="message-meta">
                <span>
                  {{
                    message.role === 'user'
                      ? '你'
                      : message.role === 'evolution'
                        ? '工作区更新'
                        : '研究助手'
                  }}
                </span>
                <time>{{ message.streaming ? streamingHint : formatClock(message.createdAt) }}</time>
              </div>
              <div v-if="message.streaming && !message.content" class="message-bubble thinking-bubble">
                <span></span><span></span><span></span>
                <em>{{ streamingHint }}</em>
              </div>
              <div v-else-if="message.waitingApproval && !message.content" class="message-bubble approval-waiting-bubble">
                研究流程已暂停，等待你处理确认请求。
              </div>
              <div v-else class="message-bubble markdown-body" v-html="renderMarkdown(message.content)"></div>
              <div v-if="message.tools?.length" class="tool-details">
                <details v-for="tool in message.tools" :key="tool.id" class="tool-detail">
                  <summary>{{ tool.name }} · {{ toolStateLabel(tool.state) }}
                    <span v-if="tool.durationMs != null"> · {{ tool.durationMs }} ms</span>
                    <span v-if="skillLoadEvidence(tool)" class="skill-evidence"> · 已读取技能内容</span>
                  </summary>
                  <p>调用 ID：{{ tool.id }}</p>
                  <strong>输入参数</strong>
                  <pre>{{ tool.input || '框架未提供参数' }}</pre>
                  <strong>返回摘要（最多 8000 字符）</strong>
                  <pre>{{ tool.output || (tool.state === 'RUNNING' ? '等待工具返回…' : '无文本返回，请结合状态判断') }}</pre>
                </details>
              </div>
              <div v-if="message.timeline?.length" class="research-timeline">
                <span class="timeline-label">执行记录</span>
                <span v-if="message.modelUsage" class="timeline-label">
                  Token 输入 {{ message.modelUsage.inputTokens }} · 输出 {{ message.modelUsage.outputTokens }}
                  · 缓存命中 {{ message.modelUsage.cachedTokens }} · 推理 {{ message.modelUsage.reasoningTokens }}
                </span>
                <div v-for="step in message.timeline" :key="step.id" class="trace-step">
                  <i class="trace-dot" :class="`trace-${step.kind}`"></i>
                  <div>
                    <strong>{{ step.title }}</strong>
                    <span v-if="step.detail">{{ step.detail }}</span>
                  </div>
                </div>
              </div>
              <section
                v-if="message.waitingApproval && pendingApproval?.messageId === message.id"
                class="approval-card"
              >
                <div class="approval-card-heading">
                  <div>
                    <span>需要确认</span>
                    <h3>{{ pendingApproval.plan ? '确认研究计划' : '确认工具操作' }}</h3>
                  </div>
                  <el-tag type="warning" effect="plain" size="small">待确认</el-tag>
                </div>
                <p v-if="pendingApproval.plan">批准这份计划后，智能体将开始委派检索和验证任务。</p>
                <p v-else>请检查下方的工具和参数。批准后将在当前租户的会话中继续执行。</p>
                <div v-for="tool in pendingApproval.tools" :key="tool.id || tool.name" class="approval-tool">
                  <div>
                    <strong>{{ tool.name }}</strong>
                    <span>{{ tool.id || '待确认调用' }}</span>
                  </div>
                  <pre>{{ formatApprovalInput(tool.input) }}</pre>
                </div>
                <div class="approval-actions">
                  <el-button
                    type="primary"
                    :loading="approvalSubmitting"
                    @click="submitApproval('allow')"
                  >
                    允许本次
                  </el-button>
                  <el-button
                    plain
                    type="danger"
                    :disabled="approvalSubmitting"
                    @click="submitApproval('deny')"
                  >
                    拒绝
                  </el-button>
                </div>
              </section>
            </div>
          </article>
        </div>

        <div class="composer-area">
          <div class="composer-box" :class="{ busy: sending }">
            <el-input
              v-model="draft"
              class="composer-textarea"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              :maxlength="12000"
              placeholder="输入研究问题；Enter 发送，Shift + Enter 换行"
              :disabled="sending || pendingApproval"
              @keydown.enter.exact.prevent="submitMessage"
            />
            <el-button
              class="send-button"
              type="primary"
              circle
              :loading="sending"
              :disabled="!draft.trim() || pendingApproval || approvalRecovering"
              :icon="Promotion"
              @click="submitMessage"
            />
          </div>
          <p>搜索结果为离线模拟数据 · 重要结论请使用真实来源复核</p>
        </div>
      </section>

      <aside class="brain-panel surface-card" :class="{ evolved: evolutionFlash }">
        <div class="panel-heading brain-heading">
          <div>
            <span class="section-kicker">工作区状态</span>
            <h2><Connection /> 研究资产</h2>
          </div>
          <el-button
            circle
            plain
            :icon="Refresh"
            :loading="statusLoading"
            @click="refreshStatus()"
          />
        </div>

        <div class="workspace-summary" :class="{ updated: evolutionFlash }">
          <div class="workspace-summary-icon"><DataAnalysis /></div>
          <div>
            <strong>{{ evolutionFlash ? '技能库已更新' : '工作区运行正常' }}</strong>
            <span>
              {{ evolutionFlash ? `${tenantId} 刚刚新增可复用技能` : `当前租户：${tenantId} · 每 4 秒自动刷新` }}
            </span>
          </div>
        </div>

        <div class="metric-grid">
          <div class="metric-card accent-violet">
            <DataAnalysis />
            <div><strong>{{ status.evolution.activeSkillCount }}</strong><span>可用技能</span></div>
          </div>
          <div class="metric-card accent-cyan">
            <CircleCheck />
            <div><strong>{{ memory.conversationCount }}</strong><span>持久会话</span></div>
          </div>
          <div class="metric-card accent-amber">
            <Search />
            <div><strong>{{ searchState.totalSearches }}</strong><span>检索次数</span></div>
          </div>
        </div>

        <section class="brain-section skills-section">
          <div class="brain-section-title">
            <div>
              <h3>技能库</h3>
              <span>租户目录与共享目录</span>
            </div>
            <el-tag size="small" effect="dark" round>{{ skills.length }}</el-tag>
          </div>

          <el-skeleton v-if="statusLoading && !hasLoadedStatus" :rows="3" animated />
          <div v-else-if="skills.length" class="skill-list">
            <div v-for="skill in skills" :key="skill.relativePath" class="skill-item">
              <div class="skill-icon"><DataAnalysis /></div>
              <div class="skill-copy">
                <div class="skill-line">
                  <button class="skill-detail-link" @click="openSkill(skill)">{{ skill.name }}</button>
                  <div class="skill-tags">
                    <el-tag type="info" size="small" effect="plain">
                      {{ skillScopeLabel(skill.scope) }}
                    </el-tag>
                    <el-tag :type="skillTagType(skill.state)" size="small" effect="plain">
                      {{ skill.state }}
                    </el-tag>
                  </div>
                </div>
                <p>{{ skill.description }}</p>
                <span>{{ skill.relativePath }} · {{ formatDateTime(skill.updatedAt) }}</span>
              </div>
            </div>
          </div>
          <div v-else class="empty-brain-state">
            <div><DataAnalysis /></div>
            <strong>尚无沉淀技能</strong>
            <span>完成复杂研究后，可复用的方法会显示在这里。</span>
          </div>
        </section>

        <section class="brain-section memory-section">
          <div class="brain-section-title">
            <div>
              <h3>记忆摘要</h3>
              <span>{{ tenantId }}/MEMORY.md + memory/*.md</span>
            </div>
            <span class="memory-count">{{ memory.dailyFileCount }} 个文件</span>
          </div>
          <div class="memory-preview markdown-body" v-html="renderMarkdown(memory.summary)"></div>
          <div class="memory-footer">
            <span>{{ memory.sessionLogCount }} 条原始会话日志</span>
            <span>{{ formatDateTime(memory.updatedAt) }}</span>
          </div>
        </section>

        <section class="brain-section recommendations-section">
          <div class="brain-section-title">
            <div>
              <h3>建议问题</h3>
              <span>根据当前租户的技能、记忆与检索记录生成</span>
            </div>
            <ChatDotRound />
          </div>
          <button
            v-for="recommendation in recommendations"
            :key="recommendation.id"
            type="button"
            class="smart-recommendation"
            :title="recommendation.prompt"
            :disabled="sending || pendingApproval"
            @click="chooseSuggestion(recommendation)"
          >
            <strong>{{ recommendation.title }}</strong>
            <span>{{ recommendation.reason }}</span>
          </button>
        </section>

        <section class="brain-section search-section">
          <div class="search-icon"><Search /></div>
          <div>
            <span>最近一次模拟检索</span>
            <strong>{{ searchState.lastQuery || '尚未调用搜索工具' }}</strong>
          </div>
          <time>{{ formatDateTime(searchState.lastSearchedAt) }}</time>
        </section>
      </aside>
    </main>

    <el-drawer
      v-model="historyDrawerVisible"
      class="history-drawer"
      direction="rtl"
      size="390px"
      :with-header="false"
    >
      <div class="history-drawer-content">
        <div class="history-drawer-heading">
          <div>
            <span class="section-kicker">已保存会话</span>
            <h2>会话历史</h2>
            <p>仅展示租户 {{ tenantId }} 的可恢复会话。</p>
          </div>
          <el-button circle plain :icon="Refresh" :loading="historyLoading" @click="loadSessionHistory()" />
        </div>

        <el-skeleton v-if="historyLoading" :rows="5" animated />
        <el-empty v-else-if="!sessionHistory.length" description="该租户还没有已持久化会话" />
        <div v-else class="history-list">
          <button
            v-for="summary in sessionHistory"
            :key="summary.sessionId"
            type="button"
            class="history-session"
            :class="{ active: summary.sessionId === sessionId }"
            :disabled="sending || pendingApproval || historyRestoring"
            @click="restoreSession(summary)"
          >
            <strong>{{ summary.title }}</strong>
            <span>{{ summary.messageCount }} 条回放消息 · {{ formatDateTime(summary.updatedAt) }}</span>
            <small>{{ summary.sessionId }}</small>
          </button>
        </div>
        <p class="history-tip">
          AgentScope 的 AgentState 用于恢复上下文；本面板只展示额外保存的用户消息和最终回答。
        </p>
      </div>
    </el-drawer>
    <el-drawer v-model="skillDrawerVisible" title="技能详情" size="min(720px, 100vw)">
      <el-skeleton v-if="skillLoading" :rows="8" animated />
      <template v-else-if="skillDetail">
        <h2>{{ skillDetail.skill.name }}</h2>
        <p>{{ skillScopeLabel(skillDetail.skill.scope) }} · {{ skillDetail.skill.state }}</p>
        <p class="skill-path">{{ skillDetail.skill.relativePath }}</p>
        <p>以下为实际 SKILL.md 文件内容；文件存在不代表本轮已加载。</p>
        <div class="markdown-body skill-document" v-html="renderMarkdown(skillDetail.content)"></div>
        <details class="tool-detail"><summary>查看完整 Markdown 原文</summary><pre>{{ skillDetail.content }}</pre></details>
      </template>
      <el-empty v-else description="未读取到技能内容" />
    </el-drawer>
  </div>
</template>
