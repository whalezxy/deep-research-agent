async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers
    }
  })

  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败（HTTP ${response.status}）`)
  }
  return payload
}

/**
 * 通过 fetch 读取 POST SSE。
 * EventSource 只支持 GET，因此聊天与审批恢复都使用 ReadableStream 自行解析 SSE 帧。
 */
async function streamPost(path, payload, onEvent) {
  const response = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(payload)
  })

  if (!response.ok) {
    const errorPayload = await response.json().catch(() => null)
    throw new Error(errorPayload?.message || `请求失败（HTTP ${response.status}）`)
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  let pausedForApproval = false
  let streamError = null

  const dispatchFrame = (frame) => {
    if (!frame.trim() || frame.startsWith(':')) return

    let eventName = 'message'
    const dataLines = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        eventName = line.slice('event:'.length).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trimStart())
      }
    }
    if (!dataLines.length) return

    let event
    try {
      event = JSON.parse(dataLines.join('\n'))
    } catch {
      throw new Error('收到无法解析的 SSE 数据')
    }

    const type = event.type || eventName
    onEvent?.({ ...event, type })
    if (type === 'complete') completed = true
    // ask 是 Harness 的正常中断点；前端显示审批卡片后由新的 POST SSE 继续本会话。
    if (type === 'ask') pausedForApproval = true
    if (type === 'error') {
      streamError = new Error(event.message || '智能体流式调用失败')
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (value) {
      buffer += decoder.decode(value, { stream: !done })
      let separator
      while ((separator = buffer.match(/\r?\n\r?\n/))) {
        const frame = buffer.slice(0, separator.index)
        buffer = buffer.slice((separator.index || 0) + separator[0].length)
        dispatchFrame(frame)
      }
    }
    if (done) break
  }

  buffer += decoder.decode()
  if (buffer.trim()) dispatchFrame(buffer)
  if (streamError) throw streamError
  if (!completed && !pausedForApproval) {
    throw new Error('流式连接已结束，但未收到完成或人工确认事件')
  }
  return { completed, pausedForApproval }
}

/** 发起一轮新的研究对话。 */
export function streamChat(message, sessionId, userId, onEvent) {
  return streamPost('/api/chat', { message, sessionId, userId }, onEvent)
}

/** 提交 ask 事件对应的 allow/deny，并流式接收 Harness 的恢复结果。 */
export function streamApproval(approvalId, sessionId, userId, decision, onEvent) {
  return streamPost('/api/agent/approval', { approvalId, sessionId, userId, decision }, onEvent)
}

export function getAgentStatus(userId) {
  const query = new URLSearchParams({ userId })
  return request(`/api/agent/status?${query}`)
}

export function getSkillDetail(userId, relativePath) {
  return request(`/api/agent/skills/detail?${new URLSearchParams({ userId, relativePath })}`)
}

export function getPendingApprovals(userId, sessionId) {
  return request(`/api/agent/approvals?${new URLSearchParams({ userId, sessionId })}`)
}

/** 当前租户的可恢复会话列表。 */
export function getSessionHistory(userId) {
  const query = new URLSearchParams({ userId })
  return request(`/api/agent/sessions?${query}`)
}

/** 获取 UI 回放用的最终会话文本。 */
export function getSessionDetail(userId, sessionId) {
  const query = new URLSearchParams({ userId })
  return request(`/api/agent/sessions/${encodeURIComponent(sessionId)}?${query}`)
}
